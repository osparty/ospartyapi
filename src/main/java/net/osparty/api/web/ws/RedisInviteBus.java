package net.osparty.api.web.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide invite delivery over Redis pub/sub. The inviter's node publishes the {@code invited}
 * frame on {@link #INVITE_CHANNEL}; every node checks whether the target is connected locally and, if so,
 * delivers it and publishes the request id back on {@link #ACK_CHANNEL}. The inviter's node completes the
 * dispatch future true on the first ack, or false after a short timeout (target offline everywhere).
 */
@Component
@Profile("!test")
public class RedisInviteBus implements InviteBus {
	private static final Logger log = LoggerFactory.getLogger(RedisInviteBus.class);

	private static final String INVITE_CHANNEL = "osparty:invite";
	private static final String ACK_CHANNEL = "osparty:invite:ack";
	private static final Duration TIMEOUT = Duration.ofMillis(800);

	private final StringRedisTemplate redis;
	private final RedisConnectionFactory connectionFactory;
	private final ObjectMapper mapper;
	private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
	private final ScheduledExecutorService timeouts =
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "invite-bus-timeout");
			t.setDaemon(true);
			return t;
		});
	private RedisMessageListenerContainer container;
	private volatile BiPredicate<String, String> localDelivery = (target, frame) -> false;

	public RedisInviteBus(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
		ObjectMapper mapper) {
		this.redis = redis;
		this.connectionFactory = connectionFactory;
		this.mapper = mapper;
	}

	@PostConstruct
	void start() {
		container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.afterPropertiesSet();
		container.addMessageListener((message, pattern) -> onInvite(new String(message.getBody(),
			java.nio.charset.StandardCharsets.UTF_8)), new ChannelTopic(INVITE_CHANNEL));
		container.addMessageListener((message, pattern) -> onAck(new String(message.getBody(),
			java.nio.charset.StandardCharsets.UTF_8)), new ChannelTopic(ACK_CHANNEL));
		container.start();
	}

	@PreDestroy
	void stop() {
		timeouts.shutdownNow();
		if (container != null) {
			container.stop();
		}
	}

	@Override
	public void setLocalDelivery(BiPredicate<String, String> localDelivery) {
		this.localDelivery = localDelivery;
	}

	@Override
	public CompletableFuture<Boolean> dispatch(String normalizedTarget, String invitedFrameJson) {
		// Fast path: the target is connected to this very node — deliver without a cluster round-trip.
		if (localDelivery.test(normalizedTarget, invitedFrameJson)) {
			return CompletableFuture.completedFuture(true);
		}
		String requestId = java.util.UUID.randomUUID().toString();
		CompletableFuture<Boolean> future = new CompletableFuture<>();
		pending.put(requestId, future);
		try {
			redis.convertAndSend(INVITE_CHANNEL,
				mapper.writeValueAsString(new Envelope(requestId, normalizedTarget, invitedFrameJson)));
		}
		catch (Exception e) {
			pending.remove(requestId);
			log.warn("invite publish failed", e);
			return CompletableFuture.completedFuture(false);
		}
		timeouts.schedule(() -> {
			CompletableFuture<Boolean> f = pending.remove(requestId);
			if (f != null) {
				f.complete(false);
			}
		}, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		return future;
	}

	private void onInvite(String payload) {
		try {
			Envelope envelope = mapper.readValue(payload, Envelope.class);
			if (localDelivery.test(envelope.target(), envelope.frame())) {
				// We hold the target's connection; tell the inviter's node it was delivered.
				redis.convertAndSend(ACK_CHANNEL, envelope.requestId());
			}
		}
		catch (Exception e) {
			log.debug("invite envelope handling failed: {}", e.toString());
		}
	}

	private void onAck(String requestId) {
		CompletableFuture<Boolean> future = pending.remove(requestId);
		if (future != null) {
			future.complete(true);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Envelope(String requestId, String target, String frame) {
	}
}

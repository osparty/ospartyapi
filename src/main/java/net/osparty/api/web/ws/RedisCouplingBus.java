package net.osparty.api.web.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import java.util.function.ToIntBiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide coupling over Redis pub/sub, shaped like {@link RedisInviteBus} but with two behaviours it
 * does not need.
 *
 * <p>{@link #anyDeviceOnline} races the same way {@code InviteBus#dispatch} does: publish a probe, let every
 * node answer for the connections it holds, settle true on the first ack or false after the timeout.
 *
 * <p>{@link #deliverCode} cannot race, because a person can have several machines live across several
 * replicas at once and every one of them has to show the code, not just the nearest. So it always publishes
 * even when this node already delivered locally, acks carry a reached-count rather than a bare id, and the
 * future sums every count instead of completing on the first reply.
 */
@Component
@Profile("!test")
public class RedisCouplingBus implements CouplingBus {
	private static final Logger log = LoggerFactory.getLogger(RedisCouplingBus.class);

	private static final String PROBE_CHANNEL = "osparty:coupling:probe";
	private static final String PROBE_ACK_CHANNEL = "osparty:coupling:probe:ack";
	private static final String DELIVER_CHANNEL = "osparty:coupling:deliver";
	private static final String DELIVER_ACK_CHANNEL = "osparty:coupling:deliver:ack";
	private static final Duration TIMEOUT = Duration.ofMillis(800);

	private final StringRedisTemplate redis;
	private final RedisConnectionFactory connectionFactory;
	private final ObjectMapper mapper;
	private final Map<String, CompletableFuture<Boolean>> pendingProbes = new ConcurrentHashMap<>();
	private final Map<String, PendingDeliver> pendingDelivers = new ConcurrentHashMap<>();
	private final ScheduledExecutorService timeouts =
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "coupling-bus-timeout");
			t.setDaemon(true);
			return t;
		});
	/**
	 * Where futures are settled, honouring {@link CouplingBus}'s thread contract.
	 *
	 * <p>Everything that completes one here does so from a thread that is not ours to occupy -- a pub/sub
	 * listener shared with invite delivery, or the single timeout scheduler above -- and whoever is waiting
	 * hangs blocking work off the answer. Completing inline would run that work on those threads. A virtual
	 * thread per completion costs nothing to start and blocks nothing when it waits.
	 */
	private final java.util.concurrent.Executor completions = Executors.newVirtualThreadPerTaskExecutor();
	// Distinguishes this node's own broadcasts from its peers' when a published message loops back on
	// the subscription this same node holds.
	private final String nodeId = UUID.randomUUID().toString();
	private RedisMessageListenerContainer container;
	private volatile LongPredicate online = accountHash -> false;
	private volatile ToIntBiFunction<Long, String> deliver = (accountHash, code) -> 0;

	public RedisCouplingBus(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
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
		container.addMessageListener((message, pattern) -> onProbe(new String(message.getBody(),
			StandardCharsets.UTF_8)), new ChannelTopic(PROBE_CHANNEL));
		container.addMessageListener((message, pattern) -> onProbeAck(new String(message.getBody(),
			StandardCharsets.UTF_8)), new ChannelTopic(PROBE_ACK_CHANNEL));
		container.addMessageListener((message, pattern) -> onDeliver(new String(message.getBody(),
			StandardCharsets.UTF_8)), new ChannelTopic(DELIVER_CHANNEL));
		container.addMessageListener((message, pattern) -> onDeliverAck(new String(message.getBody(),
			StandardCharsets.UTF_8)), new ChannelTopic(DELIVER_ACK_CHANNEL));
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
	public void setLocalHandlers(LongPredicate online, ToIntBiFunction<Long, String> deliver) {
		this.online = online;
		this.deliver = deliver;
	}

	@Override
	public CompletableFuture<Boolean> anyDeviceOnline(long accountHash) {
		// Fast path: our own connections answer without a cluster round-trip. This is the common case
		// (two devices sharing a replica) and it must not pay the timeout to find that out.
		if (online.test(accountHash)) {
			return CompletableFuture.completedFuture(true);
		}
		String requestId = UUID.randomUUID().toString();
		CompletableFuture<Boolean> future = new CompletableFuture<>();
		pendingProbes.put(requestId, future);
		try {
			redis.convertAndSend(PROBE_CHANNEL, mapper.writeValueAsString(new ProbeEnvelope(requestId, accountHash)));
		}
		catch (Exception e) {
			pendingProbes.remove(requestId);
			log.warn("coupling probe publish failed", e);
			return CompletableFuture.completedFuture(false);
		}
		timeouts.schedule(() -> {
			CompletableFuture<Boolean> f = pendingProbes.remove(requestId);
			if (f != null) {
				completions.execute(() -> f.complete(false));
			}
		}, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		return future;
	}

	@Override
	public CompletableFuture<Integer> deliverCode(long accountHash, String code) {
		int localCount = deliver.applyAsInt(accountHash, code);
		String requestId = UUID.randomUUID().toString();
		PendingDeliver pending = new PendingDeliver(localCount);
		pendingDelivers.put(requestId, pending);
		try {
			// Unlike dispatch()'s fast path, we publish even though localCount may already be positive:
			// the account's other signed-in devices can be live on other replicas right now, and every
			// one of them needs the code, not just whichever machine happens to share this node.
			redis.convertAndSend(DELIVER_CHANNEL,
				mapper.writeValueAsString(new DeliverEnvelope(requestId, accountHash, code, nodeId)));
		}
		catch (Exception e) {
			pendingDelivers.remove(requestId);
			log.warn("coupling deliver publish failed", e);
			return CompletableFuture.completedFuture(localCount);
		}
		timeouts.schedule(() -> {
			PendingDeliver p = pendingDelivers.remove(requestId);
			if (p != null) {
				completions.execute(() -> p.future.complete(p.reached.get()));
			}
		}, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		return pending.future;
	}

	private void onProbe(String payload) {
		try {
			ProbeEnvelope envelope = mapper.readValue(payload, ProbeEnvelope.class);
			if (online.test(envelope.accountHash())) {
				// We hold a live connection for the account; tell the asking node. Silence otherwise —
				// there is nothing to distinguish "we checked and no" from "we never saw the probe".
				redis.convertAndSend(PROBE_ACK_CHANNEL, envelope.requestId());
			}
		}
		catch (Exception e) {
			log.debug("coupling probe envelope handling failed: {}", e.toString());
		}
	}

	private void onProbeAck(String requestId) {
		CompletableFuture<Boolean> future = pendingProbes.remove(requestId);
		if (future != null) {
			completions.execute(() -> future.complete(true));
		}
	}

	private void onDeliver(String payload) {
		try {
			DeliverEnvelope envelope = mapper.readValue(payload, DeliverEnvelope.class);
			if (nodeId.equals(envelope.nodeId())) {
				// Every node subscribes to its own publishes. We already delivered locally and counted
				// it in deliverCode() before publishing, so acting on this copy would count this node's
				// connections twice in the final sum.
				return;
			}
			int reached = deliver.applyAsInt(envelope.accountHash(), envelope.code());
			if (reached > 0) {
				redis.convertAndSend(DELIVER_ACK_CHANNEL,
					mapper.writeValueAsString(new DeliverAck(envelope.requestId(), reached)));
			}
		}
		catch (Exception e) {
			log.debug("coupling deliver envelope handling failed: {}", e.toString());
		}
	}

	private void onDeliverAck(String payload) {
		try {
			DeliverAck ack = mapper.readValue(payload, DeliverAck.class);
			PendingDeliver pending = pendingDelivers.get(ack.requestId());
			if (pending != null) {
				// Sum rather than settle: unlike a probe this must not complete on the first ack, or
				// counts from slower replicas would be dropped and the total would undercount.
				pending.reached.addAndGet(ack.reached());
			}
		}
		catch (Exception e) {
			log.debug("coupling deliver ack handling failed: {}", e.toString());
		}
	}

	private static final class PendingDeliver {
		final CompletableFuture<Integer> future = new CompletableFuture<>();
		final AtomicInteger reached;

		PendingDeliver(int localCount) {
			this.reached = new AtomicInteger(localCount);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ProbeEnvelope(String requestId, long accountHash) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DeliverEnvelope(String requestId, long accountHash, String code, String nodeId) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DeliverAck(String requestId, int reached) {
	}
}

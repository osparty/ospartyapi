package net.osparty.api.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Redis pub/sub implementation of {@link PartyBus}, built on the same container-plus-channel shape as
 * {@link net.osparty.api.web.ws.RedisInviteBus} (PARTY_V2_MIGRATION.md §3.3 — "no new infra").
 *
 * <p>One channel carries every signal; the envelope names the room, the signal, and the node it came from.
 * A node ignores its own messages: the publisher has already applied whatever it announced, and re-applying
 * it locally would make a node drain the very room it just claimed.
 */
@Component
@Profile("!test")
public class RedisPartyBus implements PartyBus {
	private static final Logger log = LoggerFactory.getLogger(RedisPartyBus.class);

	private static final String CHANNEL = "pv2:bus";
	private static final String OWNER_CHANGED = "ownerChanged";
	private static final String FORCE_RECONNECT = "forceReconnect";

	private final StringRedisTemplate redis;
	private final RedisConnectionFactory connectionFactory;
	private final ObjectMapper mapper;
	private final String nodeId;

	private RedisMessageListenerContainer container;
	private volatile Listener listener = new Listener() {
		public void onOwnerChanged(String room, String nodeId) {
		}

		public void onForceReconnect(String room) {
		}
	};

	public RedisPartyBus(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
		ObjectMapper mapper, NodeIdentity node) {
		this.redis = redis;
		this.connectionFactory = connectionFactory;
		this.mapper = mapper;
		this.nodeId = node.nodeId();
	}

	@PostConstruct
	void start() {
		container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.afterPropertiesSet();
		container.addMessageListener(
			(message, pattern) -> onSignal(new String(message.getBody(), StandardCharsets.UTF_8)),
			new ChannelTopic(CHANNEL));
		container.start();
	}

	@PreDestroy
	void stop() {
		if (container != null) {
			container.stop();
		}
	}

	@Override
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	@Override
	public void publishOwnerChanged(String room, String nodeId) {
		publish(new Signal(OWNER_CHANGED, room, nodeId));
	}

	@Override
	public void publishForceReconnect(String room) {
		publish(new Signal(FORCE_RECONNECT, room, nodeId));
	}

	private void publish(Signal signal) {
		try {
			redis.convertAndSend(CHANNEL, mapper.writeValueAsString(signal));
		}
		catch (Exception e) {
			// A missed signal costs latency, not correctness: the losing node's next failed renewal still
			// drains the room. Never fail the caller's claim over it.
			log.debug("Party bus publish failed for {}: {}", signal.room(), e.toString());
		}
	}

	private void onSignal(String payload) {
		try {
			Signal signal = mapper.readValue(payload, Signal.class);
			if (signal.room() == null || nodeId.equals(signal.origin())) {
				return;
			}
			switch (signal.type()) {
				case OWNER_CHANGED -> listener.onOwnerChanged(signal.room(), signal.origin());
				case FORCE_RECONNECT -> listener.onForceReconnect(signal.room());
				default -> log.debug("Party bus: unknown signal {}", signal.type());
			}
		}
		catch (Exception e) {
			log.debug("Party bus signal handling failed: {}", e.toString());
		}
	}

	/** {@code origin} is the node that sent the signal — for {@code ownerChanged}, the new owner. */
	record Signal(String type, String room, String origin) {
	}
}

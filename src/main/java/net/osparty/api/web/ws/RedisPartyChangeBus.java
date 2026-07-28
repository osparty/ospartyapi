package net.osparty.api.web.ws;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/** {@link PartyChangeBus} over Redis pub/sub, the same shape as {@link RedisInviteBus}. */
@Component
@Profile("!test")
public class RedisPartyChangeBus implements PartyChangeBus {
	private static final Logger log = LoggerFactory.getLogger(RedisPartyChangeBus.class);

	private static final String CHANNEL = "osparty:ad:changed";

	private final StringRedisTemplate redis;
	private final RedisConnectionFactory connectionFactory;
	private volatile BiConsumer<String, Long> listener = (id, seq) -> { };
	private RedisMessageListenerContainer container;

	public RedisPartyChangeBus(StringRedisTemplate redis, RedisConnectionFactory connectionFactory) {
		this.redis = redis;
		this.connectionFactory = connectionFactory;
	}

	@PostConstruct
	void start() {
		container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.afterPropertiesSet();
		container.addMessageListener(
			(message, pattern) -> deliver(new String(message.getBody(), StandardCharsets.UTF_8)),
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
	public void setListener(BiConsumer<String, Long> listener) {
		this.listener = listener;
	}

	@Override
	public void publish(String partyId, long seq) {
		if (partyId == null) {
			return;
		}
		try {
			redis.convertAndSend(CHANNEL, partyId + ":" + seq);
		}
		catch (Exception e) {
			// The periodic reconcile is the backstop, so a failed announcement costs latency, not
			// correctness. Never let it take down the write that caused it.
			log.debug("ad change publish failed for {}: {}", partyId, e.toString());
		}
	}

	private void deliver(String message) {
		int at = message.lastIndexOf(':');
		if (at < 0) {
			return;
		}
		String partyId = message.substring(0, at);
		long seq;
		try {
			seq = Long.parseLong(message.substring(at + 1));
		}
		catch (NumberFormatException e) {
			return;
		}
		try {
			listener.accept(partyId, seq);
		}
		catch (Exception e) {
			log.warn("ad change listener failed for {}", partyId, e);
		}
	}
}

package net.osparty.api.service;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Coupling codes in Redis, so the pod showing the code and the pod being typed into agree about it.
 *
 * <p>{@code SET NX EX} is what makes "one pending per account" true rather than merely likely: the check and
 * the write are the same operation, so two challengers racing cannot both win.
 *
 * <p><b>Failure is closed.</b> If Redis cannot answer, no code is issued and none validates -- coupling
 * stops working until Redis is back. That is the right way round: failing open here would let a second
 * machine enrol on an account without anyone proving they can see the first.
 */
@Component
@Profile("!test")
public class RedisCouplingCodeStore implements CouplingCodeStore {
	private static final Logger log = LoggerFactory.getLogger(RedisCouplingCodeStore.class);

	private static final String PREFIX = "auth:couple:";
	/** Neither half can contain a NUL, so it cannot be smuggled across the boundary between them. */
	private static final char SEPARATOR = '\0';

	private final StringRedisTemplate redis;

	public RedisCouplingCodeStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public boolean putIfAbsent(long accountHash, String code, String challengerSessionId) {
		try {
			return Boolean.TRUE.equals(redis.opsForValue()
				.setIfAbsent(key(accountHash), code + SEPARATOR + challengerSessionId, TTL));
		}
		catch (RuntimeException e) {
			log.warn("Coupling code write failed, refusing to issue one: {}", e.toString());
			return false;
		}
	}

	@Override
	public Optional<Pending> get(long accountHash) {
		String stored;
		try {
			stored = redis.opsForValue().get(key(accountHash));
		}
		catch (RuntimeException e) {
			log.warn("Coupling code lookup failed, treating as none pending: {}", e.toString());
			return Optional.empty();
		}
		if (stored == null) {
			return Optional.empty();
		}
		int split = stored.indexOf(SEPARATOR);
		if (split < 0) {
			return Optional.empty();
		}
		return Optional.of(new Pending(stored.substring(0, split), stored.substring(split + 1)));
	}

	/**
	 * Counted in Redis rather than on the session, because a challenger that gets a free million guesses by
	 * reconnecting has not been limited at all. Failing closed here too: if the counter cannot be written we
	 * report the ceiling, which drops the pending code rather than letting an uncounted guess through.
	 */
	@Override
	public int recordFailure(long accountHash) {
		try {
			Long count = redis.opsForValue().increment(failureKey(accountHash));
			// The TTL rides the pending code's, so the counter cannot outlive what it is counting against.
			redis.expire(failureKey(accountHash), TTL);
			return count == null ? MAX_ATTEMPTS : count.intValue();
		}
		catch (RuntimeException e) {
			log.warn("Coupling failure count write failed, treating as exhausted: {}", e.toString());
			return MAX_ATTEMPTS;
		}
	}

	@Override
	public void remove(long accountHash) {
		try {
			redis.delete(failureKey(accountHash));
		}
		catch (RuntimeException e) {
			// Harmless on its own -- the counter only means anything alongside a pending code, and the TTL
			// clears it regardless.
			log.warn("Coupling failure count delete failed: {}", e.toString());
		}
		try {
			redis.delete(key(accountHash));
		}
		catch (RuntimeException e) {
			// The TTL clears it shortly anyway; the cost of missing this is one account that cannot start a
			// second coupling until the code lapses.
			log.warn("Coupling code delete failed: {}", e.toString());
		}
	}

	private static String key(long accountHash) {
		return PREFIX + accountHash;
	}

	private static String failureKey(long accountHash) {
		return PREFIX + "fail:" + accountHash;
	}
}

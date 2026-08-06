package net.osparty.api.party;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.osparty.api.service.AdvertisementFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed admission grants, in the same {@code pv2:} keyspace as party ownership. One key per grant,
 * carrying no value -- the key's existence is the whole record and its TTL is the expiry.
 *
 * <p>The room key is hashed into the Redis key rather than embedded. It is the party's passphrase, and a
 * passphrase in a key name turns anything that lists keys -- a SCAN during an incident, a slow-log entry, a
 * memory dump -- into a way to walk into every live party. Hashing also bounds the key length and keeps
 * arbitrary player-supplied text out of the keyspace.
 *
 * <p><b>Failure is closed.</b> If Redis cannot answer, the joiner is treated as ungranted and lands in
 * {@code PENDING} for the host to admit by hand. That is a slower join, not a broken one -- where failing
 * open would quietly restore the exact self-admit this class exists to remove.
 */
@Component
@Profile("!test")
public class RedisPartyAdmissionService implements PartyAdmissionService {
	private static final Logger log = LoggerFactory.getLogger(RedisPartyAdmissionService.class);

	private static final String PREFIX = "pv2:admit:";

	private final StringRedisTemplate redis;

	public RedisPartyAdmissionService(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void grant(String room, String name) {
		String key = key(room, name);
		if (key == null) {
			return;
		}
		try {
			redis.opsForValue().set(key, "1", TTL);
		}
		catch (RuntimeException e) {
			// Only the auto-admit is lost: the invite still reaches its target and the admission still
			// happened in the room. The joiner degrades to PENDING rather than failing, which is not worth
			// refusing the surrounding action over.
			log.warn("Admission grant failed, joiner will land PENDING: {}", e.toString());
		}
	}

	@Override
	public boolean isGranted(String room, String name) {
		String key = key(room, name);
		if (key == null) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(redis.hasKey(key));
		}
		catch (RuntimeException e) {
			log.warn("Admission lookup failed, treating as ungranted: {}", e.toString());
			return false;
		}
	}

	/**
	 * {@code pv2:admit:{sha256(room)}:{normalised name}}. The name stays readable because it is already
	 * public -- it is on the board -- and having it legible is what makes this debuggable at all once the
	 * room half is opaque.
	 */
	private static String key(String room, String name) {
		if (room == null || room.isBlank() || name == null || name.isBlank()) {
			return null;
		}
		return PREFIX + sha256(room) + ':' + AdvertisementFactory.normalizeHost(name);
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			// Every JVM ships SHA-256; this cannot happen on a platform that could have started.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}

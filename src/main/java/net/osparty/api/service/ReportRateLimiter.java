package net.osparty.api.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Bounds how many review messages reach Discord, regardless of what clients do.
 *
 * <h2>What this is and is not for</h2>
 * There is no account authentication anywhere in this system: {@code identify} is client-supplied
 * and host keys are client-minted. So this cannot stop a determined attacker from submitting
 * reports, and it does not try to. The goal is narrower and achievable: <em>keep the volume of
 * Discord messages bounded and roughly constant no matter how many reports arrive.</em> Reports
 * themselves are cheap rows; an unusable moderation channel is not.
 *
 * <p>The client-side "once per advertisement per session" limit in the plugin is UX, not security.
 * Everything here is the actual control.
 *
 * <h2>Layers</h2>
 * <ol>
 *   <li><b>One message per advertisement, ever</b> — the load-bearing control. An attacker
 *       reporting the entire board produces at most one message per distinct real ad, which is
 *       bounded by the board size.</li>
 *   <li><b>One message per host per cooldown</b> — so re-hosting under a fresh party id cannot be
 *       used to re-trigger notifications.</li>
 *   <li><b>Distinct-reporter threshold</b> — optionally wait for corroboration before bothering a
 *       moderator.</li>
 *   <li><b>Global circuit breaker</b> — above a per-minute ceiling, reports are still recorded but
 *       not forwarded. The audit trail stays complete while the channel stays readable.</li>
 *   <li><b>Per-IP ceiling</b> — the only semi-real identity available, and self-disabling when the
 *       ingress does not give us one. The address comes from the transport, which is the only layer
 *       that can see it (see {@code NettySocketHandler#clientIp}).</li>
 * </ol>
 *
 * <p>Every counter lives in Redis so the limits hold across all replicas, and every one of them
 * fails <em>open</em> on a Redis error: reporting is a safety feature, and breaking it because a
 * cache is unavailable helps nobody.
 */
@Service
public class ReportRateLimiter {
	private static final Logger log = LoggerFactory.getLogger(ReportRateLimiter.class);

	private static final String REPORTERS_KEY = "reports:party:";
	private static final String NOTIFIED_PARTY_KEY = "reports:notified:party:";
	private static final String NOTIFIED_HOST_KEY = "reports:notified:host:";
	private static final String GLOBAL_KEY = "reports:global:";
	private static final String IP_KEY = "reports:ip:";

	private static final Duration GLOBAL_BUCKET_TTL = Duration.ofSeconds(120);
	private static final Duration IP_BUCKET_TTL = Duration.ofHours(1);
	private static final int IP_HOURLY_MAX = 10;

	private final StringRedisTemplate redis;
	private final Duration adTtl;
	private final Duration hostCooldown;
	private final int notifyThreshold;
	private final int globalPerMinute;
	private final String ipPepper;

	public ReportRateLimiter(StringRedisTemplate redis,
		@Value("${app.ads.ttl-ms:90000}") long adTtlMs,
		@Value("${app.reports.per-party-cooldown-ms:1800000}") long hostCooldownMs,
		@Value("${app.reports.notify-threshold:1}") int notifyThreshold,
		@Value("${app.reports.global-per-minute:20}") int globalPerMinute,
		@Value("${app.reports.ip-hash-pepper:}") String ipPepper) {
		this.redis = redis;
		this.adTtl = Duration.ofMillis(adTtlMs);
		this.hostCooldown = Duration.ofMillis(hostCooldownMs);
		this.notifyThreshold = notifyThreshold;
		this.globalPerMinute = globalPerMinute;
		this.ipPepper = ipPepper == null ? "" : ipPepper;
	}

	/**
	 * Records this reporter's interest and decides whether a review message should be posted.
	 *
	 * @param fingerprint whatever identifies the reporter best — account hash, name, or session id
	 * @return the decision, including how many distinct clients have now reported the ad
	 */
	public Decision evaluate(String partyId, String normalizedHost, String fingerprint) {
		long distinct;
		try {
			Long added = redis.opsForSet().add(REPORTERS_KEY + partyId, fingerprint);
			redis.expire(REPORTERS_KEY + partyId, adTtl);
			if (added != null && added == 0) {
				// This client already reported this ad on a previous session. Recorded, not renotified.
				Long size = redis.opsForSet().size(REPORTERS_KEY + partyId);
				return Decision.suppressed("duplicate", size == null ? 1 : size.intValue());
			}
			Long size = redis.opsForSet().size(REPORTERS_KEY + partyId);
			distinct = size == null ? 1 : size;
		}
		catch (Exception e) {
			log.debug("Report dedupe unavailable, allowing through: {}", e.toString());
			distinct = 1;
		}

		if (distinct < notifyThreshold) {
			return Decision.suppressed("below-threshold", (int) distinct);
		}
		if (!claim(NOTIFIED_PARTY_KEY + partyId, adTtl)) {
			return Decision.suppressed("already-notified-party", (int) distinct);
		}
		if (!claim(NOTIFIED_HOST_KEY + normalizedHost, hostCooldown)) {
			return Decision.suppressed("host-cooldown", (int) distinct);
		}
		if (!withinGlobalBudget()) {
			return Decision.suppressed("global-cap", (int) distinct);
		}
		return Decision.notify((int) distinct);
	}

	/**
	 * Whether this client is under its hourly ceiling. Returns true when no client address was
	 * captured at handshake: without one every client shares a bucket, and enforcing that would
	 * throttle the whole user base rather than any abuser.
	 */
	public boolean withinIpBudget(String clientIp) {
		if (clientIp == null || clientIp.isBlank()) {
			return true;
		}
		try {
			String key = IP_KEY + hash(clientIp) + ":" + (System.currentTimeMillis() / 3_600_000L);
			Long count = redis.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redis.expire(key, IP_BUCKET_TTL);
			}
			return count == null || count <= IP_HOURLY_MAX;
		}
		catch (Exception e) {
			log.debug("Per-IP report budget unavailable, allowing through: {}", e.toString());
			return true;
		}
	}

	/** sha256(ip + pepper), so the stored value cannot be reversed into an address. */
	public String hash(String clientIp) {
		if (clientIp == null || clientIp.isBlank()) {
			return null;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(clientIp.getBytes(StandardCharsets.UTF_8));
			digest.update(ipPepper.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/** SET NX EX: the first caller wins the slot, everyone else is told it is taken. */
	private boolean claim(String key, Duration ttl) {
		try {
			return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
		}
		catch (Exception e) {
			log.debug("Report notification claim unavailable, allowing through: {}", e.toString());
			return true;
		}
	}

	private boolean withinGlobalBudget() {
		try {
			String key = GLOBAL_KEY + (System.currentTimeMillis() / 60_000L);
			Long count = redis.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redis.expire(key, GLOBAL_BUCKET_TTL);
			}
			return count == null || count <= globalPerMinute;
		}
		catch (Exception e) {
			log.debug("Global report budget unavailable, allowing through: {}", e.toString());
			return true;
		}
	}

	/**
	 * @param shouldNotify whether to post a review message
	 * @param reason why not, for metrics; null when notifying
	 * @param distinctReporters distinct clients that have reported this advertisement
	 */
	public record Decision(boolean shouldNotify, String reason, int distinctReporters) {
		static Decision notify(int distinctReporters) {
			return new Decision(true, null, distinctReporters);
		}

		static Decision suppressed(String reason, int distinctReporters) {
			return new Decision(false, reason, distinctReporters);
		}
	}
}

package net.osparty.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The rate limiter against a real Redis, because its whole job is expressed in Redis primitives
 * (SADD cardinality, SET NX EX, INCR + EXPIRE) and a fake would only assert that the fake works.
 *
 * <p>Runs against {@code localhost:6379} — the same instance {@code docker compose up redis}
 * provides — and skips when nothing is listening, so the ordinary suite stays self-contained. Keys
 * are namespaced per run so it is safe against a shared development instance.
 *
 * <p>Note the counterpart to this: everywhere else in the suite there is no Redis at all, which
 * exercises the fail-open paths. Both directions matter — reporting must not break when Redis is
 * down, and must actually bound Discord volume when it is up.
 */
@EnabledIf("redisReachable")
class ReportRateLimiterTest {
	private static final String HOST = "localhost";
	private static final int PORT = 6379;

	private static LettuceConnectionFactory factory;
	private static StringRedisTemplate redis;

	static boolean redisReachable() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(HOST, PORT), 300);
			return true;
		}
		catch (IOException e) {
			return false;
		}
	}

	@BeforeAll
	static void connect() {
		factory = new LettuceConnectionFactory(HOST, PORT);
		factory.afterPropertiesSet();
		redis = new StringRedisTemplate(factory);
		redis.afterPropertiesSet();
	}

	@AfterAll
	static void disconnect() {
		factory.destroy();
	}

	/** notifyThreshold 1, a long host cooldown, and a generous global cap unless overridden. */
	private ReportRateLimiter limiter(int notifyThreshold, int globalPerMinute) {
		return new ReportRateLimiter(redis, 90_000, 1_800_000, notifyThreshold, globalPerMinute, "pepper");
	}

	private static String uniqueParty() {
		return "party-" + UUID.randomUUID();
	}

	private static String uniqueHost() {
		return "host-" + UUID.randomUUID();
	}

	@Test
	void firstReportNotifiesAndFurtherReportsOfTheSameAdDoNot() {
		ReportRateLimiter limiter = limiter(1, 1000);
		String party = uniqueParty();
		String host = uniqueHost();

		assertThat(limiter.evaluate(party, host, "reporter-a").shouldNotify()).isTrue();

		// A different reporter, same advertisement: recorded, but no second Discord message.
		ReportRateLimiter.Decision second = limiter.evaluate(party, host, "reporter-b");
		assertThat(second.shouldNotify()).isFalse();
		assertThat(second.reason()).isEqualTo("already-notified-party");
		assertThat(second.distinctReporters()).isEqualTo(2);
	}

	@Test
	void theSameReporterTwiceIsDeduplicatedAndNotRecountedAsCorroboration() {
		ReportRateLimiter limiter = limiter(1, 1000);
		String party = uniqueParty();
		String host = uniqueHost();

		limiter.evaluate(party, host, "reporter-a");
		ReportRateLimiter.Decision repeat = limiter.evaluate(party, host, "reporter-a");

		assertThat(repeat.shouldNotify()).isFalse();
		assertThat(repeat.reason()).isEqualTo("duplicate");
		assertThat(repeat.distinctReporters()).isEqualTo(1);
	}

	@Test
	void aThresholdAboveOneWaitsForCorroborationBeforeNotifying() {
		ReportRateLimiter limiter = limiter(2, 1000);
		String party = uniqueParty();
		String host = uniqueHost();

		ReportRateLimiter.Decision first = limiter.evaluate(party, host, "reporter-a");
		assertThat(first.shouldNotify()).isFalse();
		assertThat(first.reason()).isEqualTo("below-threshold");

		assertThat(limiter.evaluate(party, host, "reporter-b").shouldNotify()).isTrue();
	}

	/**
	 * The control that stops a banned advertiser from re-triggering a review message simply by
	 * disbanding and re-hosting, which produces a brand new party id every time.
	 */
	@Test
	void reHostingUnderANewPartyIdIsStillHeldByTheHostCooldown() {
		ReportRateLimiter limiter = limiter(1, 1000);
		String host = uniqueHost();

		assertThat(limiter.evaluate(uniqueParty(), host, "reporter-a").shouldNotify()).isTrue();

		ReportRateLimiter.Decision reHosted = limiter.evaluate(uniqueParty(), host, "reporter-a");
		assertThat(reHosted.shouldNotify()).isFalse();
		assertThat(reHosted.reason()).isEqualTo("host-cooldown");
	}

	/**
	 * The global counter is a single shared per-minute bucket — that is the point of it, so it
	 * cannot be namespaced per test. Cleared here, then asserted by draining rather than by exact
	 * position, so a minute rolling over mid-test costs an extra iteration instead of a failure.
	 */
	@Test
	void theGlobalCircuitBreakerStopsNotifyingOncePastTheCeiling() {
		int cap = 3;
		ReportRateLimiter limiter = limiter(1, cap);
		redis.delete("reports:global:" + (System.currentTimeMillis() / 60_000L));

		ReportRateLimiter.Decision decision = null;
		int notified = 0;
		for (int i = 0; i < cap * 2 + 2; i++) {
			decision = limiter.evaluate(uniqueParty(), uniqueHost(), "r");
			if (decision.shouldNotify()) {
				notified++;
			}
			else if ("global-cap".equals(decision.reason())) {
				break;
			}
		}

		assertThat(decision).isNotNull();
		assertThat(decision.reason()).isEqualTo("global-cap");
		assertThat(notified).isBetween(1, cap);
	}

	/**
	 * The IP bucket is hourly, so the address has to be unique per run or a second run inside the
	 * same hour starts already spent. The limiter treats the address as an opaque key it hashes, so
	 * a UUID stands in for one perfectly well.
	 */
	@Test
	void perIpBudgetAllowsTenPerHourThenStops() {
		ReportRateLimiter limiter = limiter(1, 1000);
		String ip = "test-ip-" + UUID.randomUUID();

		for (int i = 1; i <= 10; i++) {
			assertThat(limiter.withinIpBudget(ip)).as("report %d", i).isTrue();
		}
		assertThat(limiter.withinIpBudget(ip)).isFalse();
	}

	/**
	 * Without a forwarded header every client looks identical, so the per-IP layer must disable
	 * itself rather than throttle the entire user base into one bucket.
	 */
	@Test
	void perIpBudgetIsInactiveWhenNoClientAddressWasCaptured() {
		ReportRateLimiter limiter = limiter(1, 1000);
		for (int i = 0; i < 50; i++) {
			assertThat(limiter.withinIpBudget(null)).isTrue();
		}
	}

	@Test
	void ipHashIsStableSaltedAndNotReversible() {
		ReportRateLimiter limiter = limiter(1, 1000);
		ReportRateLimiter other =
			new ReportRateLimiter(redis, 90_000, 1_800_000, 1, 1000, "a-different-pepper");

		String hash = limiter.hash("198.51.100.7");
		assertThat(hash).hasSize(64).doesNotContain("198.51.100.7");
		assertThat(limiter.hash("198.51.100.7")).isEqualTo(hash);
		assertThat(other.hash("198.51.100.7")).isNotEqualTo(hash);
		assertThat(limiter.hash(null)).isNull();
	}
}

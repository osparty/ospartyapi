package net.osparty.api.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.osparty.api.model.AdReport;
import net.osparty.api.repository.InMemoryAdReportRepository;
import net.osparty.api.repository.InMemoryBanRepository;
import net.osparty.api.service.AdReportService;
import net.osparty.api.service.BanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Report ingress over the socket.
 *
 * <p>Reporting is fire-and-forget by design — the client is never told whether a report was
 * recorded, deduplicated or throttled, because an acknowledgement that distinguished those would
 * tell an abuser exactly which of their reports landed. That makes every assertion here an
 * observation of the server's own state rather than of a reply frame.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"app.ws.reconcile-interval-ms=150",
	"app.bans.refresh-ms=100",
	"app.reports.notify-threshold=1",
	// The global circuit breaker is a single shared per-minute bucket in Redis, so a developer
	// machine running the whole suite against one instance can exhaust it before this class starts.
	// Raised out of the way here; ReportRateLimiterTest is where that ceiling is actually tested.
	"app.reports.global-per-minute=1000000"})
class AdReportSocketTest {
	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private InMemoryAdReportRepository reports;

	@Autowired
	private InMemoryBanRepository banRepository;

	@Autowired
	private BanService bans;

	@Autowired
	private RecordingAdReportService published;

	@Autowired
	private org.springframework.data.redis.core.StringRedisTemplate redis;

	@TestConfiguration
	static class Config {
		@Bean
		@Primary
		RecordingAdReportService recordingAdReportService() {
			return new RecordingAdReportService();
		}
	}

	/**
	 * Host names are unique per run because the notify-once-per-host cooldown is a genuinely global,
	 * half-hour Redis key — reusing a fixed name would make a passing run poison the next one.
	 */
	private static String uniqueHost(String prefix) {
		return prefix + java.util.UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * Clears the rate limiter's Redis state before each case.
	 *
	 * <p>Necessary because {@code FakePartyRepository}'s id sequence restarts with the JVM, so party
	 * ids repeat from run to run — and the limiter's per-party keys, correctly, do not. Without this
	 * a passing run leaves behind "already notified about party 1001" and fails the next one.
	 */
	@BeforeEach
	void clearRateLimiterState() {
		java.util.Set<String> keys = redis.keys("reports:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@AfterEach
	void reset() {
		reports.clear();
		published.requests.clear();
		banRepository.clear();
		bans.refresh();
	}

	@Test
	void reportPersistsTheAdvertisementAndNotifiesOnce() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		BlockingQueue<JsonNode> b = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		WebSocketSession reporterSession = connect(b);
		try {
			String hostName = uniqueHost("Advertiser");
			String id = host(hostSession, a, hostName, "k-rep", "cheap fire cape service");
			identify(reporterSession, 55L, "ConcernedPlayer");

			report(reporterSession, id);

			awaitCondition(() -> !reports.all().isEmpty(), "report persisted");
			AdReport stored = reports.all().get(0);
			assertThat(stored.getPartyId()).isEqualTo(id);
			assertThat(stored.getHostName()).isEqualTo(hostName.toLowerCase());
			assertThat(stored.getHostNameRaw()).isEqualTo(hostName);
			assertThat(stored.getDescription()).isEqualTo("cheap fire cape service");
			assertThat(stored.getReporterName()).isEqualTo("concernedplayer");
			assertThat(stored.getReporterAccountHash()).isEqualTo(55L);
			assertThat(stored.getStatus()).isEqualTo(AdReport.STATUS_PENDING);
			// The whole advertisement is kept verbatim: it is the only evidence once the ad TTLs out.
			assertThat(stored.getAdSnapshot()).contains("\"host\":\"" + hostName + "\"");

			awaitCondition(() -> !published.requests.isEmpty(), "review published");
			assertThat(published.requests).hasSize(1);
			assertThat(published.requests.get(0).host()).isEqualTo(hostName);
			assertThat(published.requests.get(0).reportId()).isEqualTo(stored.getId());
			awaitCondition(() -> reports.all().get(0).isNotified(), "report marked notified");
		}
		finally {
			close(hostSession, reporterSession);
		}
	}

	@Test
	void reportingTheSameAdTwiceOnOneSessionRecordsAndNotifiesOnce() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		BlockingQueue<JsonNode> b = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		WebSocketSession reporterSession = connect(b);
		try {
			String id = host(hostSession, a, uniqueHost("RepeatTarget"), "k-dup", "spam");
			identify(reporterSession, 56L, "Repeater");

			report(reporterSession, id);
			awaitCondition(() -> !reports.all().isEmpty(), "first report persisted");
			report(reporterSession, id);
			settle();

			assertThat(reports.all()).hasSize(1);
			assertThat(published.requests).hasSize(1);
		}
		finally {
			close(hostSession, reporterSession);
		}
	}

	@Test
	void aHostCannotReportTheirOwnAdvertisement() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		try {
			String id = host(hostSession, a, uniqueHost("SelfReporter"), "k-self", "mine");

			report(hostSession, id);
			settle();

			assertThat(reports.all()).isEmpty();
			assertThat(published.requests).isEmpty();
		}
		finally {
			close(hostSession);
		}
	}

	@Test
	void reportingAnUnknownPartyIsIgnored() throws Exception {
		BlockingQueue<JsonNode> b = new LinkedBlockingQueue<>();
		WebSocketSession reporterSession = connect(b);
		try {
			report(reporterSession, "no-such-party");
			settle();

			assertThat(reports.all()).isEmpty();
			assertThat(published.requests).isEmpty();
		}
		finally {
			close(reporterSession);
		}
	}

	/** Re-reporting a host already dealt with would just re-notify moderators about a closed case. */
	@Test
	void reportingAnAlreadyBannedAdIsIgnored() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		BlockingQueue<JsonNode> b = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		WebSocketSession reporterSession = connect(b);
		try {
			String hostName = uniqueHost("AlreadyHandled");
			String id = host(hostSession, a, hostName, "k-banned", "spam");
			bans.ban(hostName, 0, "boosting", "1", "mod", null);
			identify(reporterSession, 57L, "LateReporter");

			report(reporterSession, id);
			settle();

			assertThat(reports.all()).isEmpty();
			assertThat(published.requests).isEmpty();
		}
		finally {
			close(hostSession, reporterSession);
		}
	}

	// --- helpers -------------------------------------------------------------------------------

	private void report(WebSocketSession session, String partyId) throws Exception {
		send(session, "{\"type\":\"report\",\"id\":\"" + partyId + "\"}");
	}

	private void identify(WebSocketSession session, long accountHash, String name) throws Exception {
		send(session, "{\"type\":\"identify\",\"accountHash\":" + accountHash
			+ ",\"name\":\"" + name + "\"}");
	}

	private String host(WebSocketSession session, BlockingQueue<JsonNode> messages,
		String host, String key, String description) throws Exception {
		send(session, "{\"type\":\"host\",\"key\":\"" + key + "\",\"request\":"
			+ "{\"activity\":\"cox\",\"host\":\"" + host + "\",\"capacity\":3,"
			+ "\"description\":\"" + description + "\",\"passphrase\":\"pp-" + key + "\"}}");
		return awaitWhere(messages, m -> "hosted".equals(m.path("type").asText()), "hosted ack")
			.path("party").path("id").asText();
	}

	private WebSocketSession connect(BlockingQueue<JsonNode> messages) throws Exception {
		return new StandardWebSocketClient().execute(
			new TextWebSocketHandler() {
				@Override
				protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
					messages.add(mapper.readTree(m.getPayload()));
				}
			},
			"ws://localhost:" + port + "/api/v1/ws/parties").get(5, TimeUnit.SECONDS);
	}

	private static void send(WebSocketSession session, String json) throws Exception {
		session.sendMessage(new TextMessage(json));
	}

	private static void close(WebSocketSession... sessions) throws Exception {
		for (WebSocketSession session : sessions) {
			session.close();
		}
	}

	/** Gives an intentionally-silent server long enough to have done nothing. */
	private static void settle() throws Exception {
		Thread.sleep(400);
	}

	private static JsonNode awaitWhere(BlockingQueue<JsonNode> messages, Predicate<JsonNode> match,
		String description) {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			JsonNode msg;
			try {
				msg = messages.poll(100, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted waiting for " + description, e);
			}
			if (msg != null && match.test(msg)) {
				return msg;
			}
		}
		throw new AssertionError("Timed out waiting for " + description);
	}

	private static void awaitCondition(java.util.function.BooleanSupplier condition, String description) {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted waiting for " + description, e);
			}
		}
		throw new AssertionError("Timed out waiting for " + description);
	}

	static class RecordingAdReportService implements AdReportService {
		final List<ReviewRequest> requests = new CopyOnWriteArrayList<>();

		@Override
		public Optional<PostedReview> publish(ReviewRequest request) {
			requests.add(request);
			return Optional.of(new PostedReview("chan-1", "msg-" + request.reportId()));
		}
	}
}

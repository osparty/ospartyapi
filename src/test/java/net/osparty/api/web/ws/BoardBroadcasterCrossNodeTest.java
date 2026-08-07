package net.osparty.api.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;
import java.util.function.ToIntBiFunction;
import net.osparty.api.party.LocalPartyAdmissionService;
import net.osparty.api.repository.FakeAdvertisementRepository;
import net.osparty.api.repository.InMemoryAccountCredentialRepository;
import net.osparty.api.repository.InMemoryAccountRecoveryRepository;
import net.osparty.api.repository.InMemoryAdReportRepository;
import net.osparty.api.service.AccountAuthService;
import net.osparty.api.service.DisabledAdReportService;
import net.osparty.api.service.DisabledVoiceChannelService;
import net.osparty.api.service.LocalCouplingCodeStore;
import net.osparty.api.service.PlayerIdService;
import net.osparty.api.transport.SocketSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coupling between two devices that are not on the same replica -- which in production is most of them.
 *
 * <p>Nothing pins a person's two machines to one instance: session membership is an in-memory map per
 * process, there is no session affinity at the ingress, and the node-hint route covers live party rooms
 * rather than the board socket. With three replicas two devices share one about a third of the time, so a
 * sweep of the local map missed the other machine in roughly two cases out of three and coupling reported
 * itself unavailable while the user was looking straight at the device that should have shown the code.
 *
 * <p>Every other test here runs one {@link BoardBroadcaster}, which is exactly the arrangement that made the
 * fault invisible. This one runs two, sharing the stores they would share in a cluster and a bus that fans
 * out the way Redis pub/sub does -- so the connections genuinely live in different instances and a local
 * sweep genuinely cannot see across.
 */
class BoardBroadcasterCrossNodeTest {
	private static final long ACCOUNT = 4242L;

	private final ObjectMapper mapper = new ObjectMapper();
	private AccountAuthService auth;
	private TwoNodeCouplingBus bus;
	private BoardBroadcaster nodeA;
	private BoardBroadcaster nodeB;
	private CollectingSession desktop;
	private CollectingSession laptop;

	@BeforeEach
	void setUp() {
		// One service over one set of stores, as Postgres and Redis are shared by every replica. Only the
		// socket registries are per-instance, which is the whole point of the arrangement.
		auth = new AccountAuthService(new InMemoryAccountCredentialRepository(),
			new InMemoryAccountRecoveryRepository(),
			new LocalCouplingCodeStore(), true);
		bus = new TwoNodeCouplingBus();
		nodeA = node();
		nodeB = node();

		desktop = new CollectingSession("desktop");
		laptop = new CollectingSession("laptop");
		nodeA.onOpen(desktop, "1.2.3.4");
		nodeB.onOpen(laptop, "5.6.7.8");
	}

	private BoardBroadcaster node() {
		return new BoardBroadcaster(new FakeAdvertisementRepository(), mapper,
			new DisabledVoiceChannelService(),
			null, null, // DiscordLinkService, DiscordBadgeService: unreachable from these frames
			new LocalPresenceRegistry(),
			new LocalInviteBus(),
			bus,
			null, // BanService: unreachable from these frames
			new LocalBoardChangeBus(),
			new LocalPartyAdmissionService(),
			auth,
			null, // DiscordRecoveryService: unreachable from these frames
			new PlayerIdService("test-salt"),
			true, true,
			new InMemoryAdReportRepository(),
			new DisabledAdReportService(),
			null, // ReportRateLimiter: unreachable from these frames, needs real Redis to build
			true, 5,
			new SimpleMeterRegistry());
	}

	/**
	 * The failure the bus exists to fix, stated as the thing the user sees: their other machine is running,
	 * and the client was told the code route did not exist.
	 */
	@Test
	void aDeviceOnAnotherNodeCountsAsOnline() throws Exception {
		identify(nodeA, desktop, ACCOUNT);

		identify(nodeB, laptop, ACCOUNT);

		assertThat(last(laptop, "authFailed").path("coupling").asBoolean()).isTrue();
	}

	@Test
	void theCodeReachesADeviceOnAnotherNode() throws Exception {
		identify(nodeA, desktop, ACCOUNT);
		identify(nodeB, laptop, ACCOUNT);
		clear(desktop);

		requestCode(nodeB, laptop, ACCOUNT);

		assertThat(last(desktop, "couplingCode").path("code").asText()).matches("\\d{6}");
		assertThat(last(laptop, "couplingCodeSent").path("reached").asInt()).isEqualTo(1);
	}

	/** The whole round trip: two machines, two replicas, one account, ending in a credential. */
	@Test
	void couplingCompletesAcrossNodes() throws Exception {
		identify(nodeA, desktop, ACCOUNT);
		identify(nodeB, laptop, ACCOUNT);
		requestCode(nodeB, laptop, ACCOUNT);
		String code = last(desktop, "couplingCode").path("code").asText();

		nodeB.onMessage(laptop.id(), "{\"type\":\"couplingConfirm\",\"accountHash\":" + ACCOUNT
			+ ",\"code\":\"" + code + "\"}");

		assertThat(last(laptop, "authIssued").path("token").asText()).isNotBlank();
		assertThat(last(laptop, "couplingResult").path("success").asBoolean()).isTrue();
	}

	/**
	 * The code goes to every one of the account's machines, wherever they are -- the person may be sitting at
	 * any of them, and one that cannot see a screen showing it is no better off than one that is offline.
	 */
	@Test
	void everyDeviceOnTheAccountIsShownTheCode() throws Exception {
		identify(nodeA, desktop, ACCOUNT);
		CollectingSession tablet = new CollectingSession("tablet");
		nodeB.onOpen(tablet, "7.7.7.7");
		identify(nodeB, tablet, ACCOUNT);
		assertThat(last(desktop, "couplingCode")).as("nothing is minted before it is asked for").isNull();
		// Couple the tablet so the account has two signed-in machines, one per node.
		requestCode(nodeB, tablet, ACCOUNT);
		nodeB.onMessage(tablet.id(), "{\"type\":\"couplingConfirm\",\"accountHash\":" + ACCOUNT
			+ ",\"code\":\"" + last(desktop, "couplingCode").path("code").asText() + "\"}");
		clear(desktop);
		clear(tablet);

		identify(nodeB, laptop, ACCOUNT);
		requestCode(nodeB, laptop, ACCOUNT);

		assertThat(last(desktop, "couplingCode")).as("the machine on the other node").isNotNull();
		assertThat(last(tablet, "couplingCode")).as("the machine on this node").isNotNull();
		assertThat(last(laptop, "couplingCodeSent").path("reached").asInt()).isEqualTo(2);
	}

	/** With nothing signed in anywhere, the answer is still "no" -- the bus must not invent presence. */
	@Test
	void noDeviceAnywhereIsReportedHonestly() throws Exception {
		auth.enrol(ACCOUNT, null, null);

		identify(nodeB, laptop, ACCOUNT);

		assertThat(last(laptop, "authFailed").path("coupling").asBoolean()).isFalse();
		assertThat(last(laptop, "authIssued")).isNull();
	}

	private void identify(BoardBroadcaster node, CollectingSession session, long accountHash) {
		node.onMessage(session.id(), "{\"type\":\"identify\",\"accountHash\":" + accountHash + "}");
	}

	private void requestCode(BoardBroadcaster node, CollectingSession session, long accountHash) {
		node.onMessage(session.id(),
			"{\"type\":\"requestCouplingCode\",\"accountHash\":" + accountHash + "}");
	}

	private void clear(CollectingSession session) {
		session.out.clear();
	}

	private JsonNode last(CollectingSession session, String type) throws Exception {
		JsonNode found = null;
		for (String json : session.out) {
			JsonNode node = mapper.readTree(json);
			if (type.equals(node.path("type").asText())) {
				found = node;
			}
		}
		return found;
	}

	/**
	 * A bus that fans out to every registered node, as Redis pub/sub does.
	 *
	 * <p>Collects handlers instead of replacing them, which is the one thing that makes two broadcasters in
	 * one JVM behave like two processes: each answers only for its own connections, and the bus is the only
	 * thing that can see both. Synchronous, so a test asserts on what actually happened rather than on a
	 * timeout -- the real implementation's ack window is its own concern.
	 */
	private static final class TwoNodeCouplingBus implements CouplingBus {
		private final List<LongPredicate> online = new ArrayList<>();
		private final List<ToIntBiFunction<Long, String>> deliver = new ArrayList<>();

		@Override
		public void setLocalHandlers(LongPredicate online, ToIntBiFunction<Long, String> deliver) {
			this.online.add(online);
			this.deliver.add(deliver);
		}

		@Override
		public CompletableFuture<Boolean> anyDeviceOnline(long accountHash) {
			return CompletableFuture.completedFuture(
				online.stream().anyMatch(node -> node.test(accountHash)));
		}

		@Override
		public CompletableFuture<Integer> deliverCode(long accountHash, String code) {
			int reached = 0;
			for (ToIntBiFunction<Long, String> node : deliver) {
				reached += node.applyAsInt(accountHash, code);
			}
			return CompletableFuture.completedFuture(reached);
		}
	}

	/** A connection with no transport under it; the frames it was sent are all a test here needs. */
	private static final class CollectingSession implements SocketSession {
		private final String id;
		private final List<String> out = new ArrayList<>();

		CollectingSession(String id) {
			this.id = id;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public boolean isOpen() {
			return true;
		}

		@Override
		public void send(byte[] frame) {
			out.add(new String(frame, StandardCharsets.UTF_8));
		}

		@Override
		public void sendText(String json) {
			out.add(json);
		}

		@Override
		public void close() {
		}

		@Override
		public boolean nodeHinted() {
			return false;
		}
	}
}

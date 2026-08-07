package net.osparty.api.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.osparty.api.party.LocalPartyAdmissionService;
import net.osparty.api.repository.AccountCredentialRepository;
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
 * The coupling conversation as it actually runs through {@link BoardBroadcaster}, not just the service
 * behind it. {@link net.osparty.api.service.AccountAuthTest} covers the state machine in isolation; this is
 * what pinned down the first version's real bugs -- the code was mailed to the wrong session, the incumbent
 * sweep matched on an unauthenticated claim, and a session was told it had lost the credential it was just
 * given. None of those are visible from the service alone, because they are all about which {@code Subscriber}
 * a frame reaches.
 */
class BoardBroadcasterCouplingTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private AccountAuthService auth;
	private BoardBroadcaster board;
	private CollectingSession desktop;
	private CollectingSession laptop;

	@BeforeEach
	void setUp() {
		auth = new AccountAuthService(new InMemoryAccountCredentialRepository(),
			new InMemoryAccountRecoveryRepository(),
			new LocalCouplingCodeStore(), true);
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		FakeAdvertisementRepository store = new FakeAdvertisementRepository();
		board = new BoardBroadcaster(store, mapper,
			new DisabledVoiceChannelService(),
			null, // DiscordLinkService: unreachable from identify/couplingConfirm
			null, // DiscordBadgeService: unreachable from identify/couplingConfirm
			new LocalPresenceRegistry(),
			new LocalInviteBus(),
			new LocalCouplingBus(),
			null, // BanService: unreachable from identify/couplingConfirm
			new LocalBoardChangeBus(),
			new LocalPartyAdmissionService(),
			auth,
			null, // DiscordRecoveryService: unreachable from identify/couplingConfirm
			new PlayerIdService("test-salt"),
			true, true,
			new InMemoryAdReportRepository(),
			new DisabledAdReportService(),
			null, // ReportRateLimiter: unreachable from identify/couplingConfirm, needs real Redis to build
			true, 5,
			meters);

		desktop = new CollectingSession("desktop");
		laptop = new CollectingSession("laptop");
		board.onOpen(desktop, "1.2.3.4");
		board.onOpen(laptop, "5.6.7.8");
	}

	@Test
	void firstIdentifyOnAnAccountEnrolsWithoutACode() throws Exception {
		identify(desktop, 4242L);

		JsonNode issued = last(desktop, "authIssued");
		assertThat(issued).isNotNull();
		assertThat(issued.path("token").asText()).isNotBlank();
	}

	/**
	 * The bug this pins down: the code used to be mailed to the challenger in the same frame that told it a
	 * code was needed, which made the whole check answer itself.
	 */
	@Test
	void theChallengerIsNeverSentTheCode() throws Exception {
		identify(desktop, 4242L);
		clear(desktop);

		identify(laptop, 4242L);

		JsonNode failed = last(laptop, "authFailed");
		assertThat(failed).isNotNull();
		assertThat(failed.has("code")).isFalse();
		assertThat(failed.path("coupling").asBoolean()).isTrue();
	}

	@Test
	void theCodeGoesOnlyToTheAuthenticatedIncumbent() throws Exception {
		identify(desktop, 4242L);
		clear(desktop);
		identify(laptop, 4242L);

		requestCode(laptop, 4242L);

		JsonNode code = last(desktop, "couplingCode");
		assertThat(code).isNotNull();
		assertThat(code.path("code").asText()).matches("\\d{6}");
		assertThat(last(laptop, "couplingCodeSent").path("reached").asInt()).isEqualTo(1);
	}

	/**
	 * Failing to sign in must not, on its own, put anything on the owner's screen. It used to: a stranger who
	 * merely named an account hash could make a code dialog appear in front of whoever really held it, over
	 * and over. Now the code exists only once a person has asked for one.
	 */
	@Test
	void noCodeAppearsUntilSomebodyAsksForOne() throws Exception {
		identify(desktop, 4242L);
		clear(desktop);

		identify(laptop, 4242L);

		assertThat(last(laptop, "authFailed").path("coupling").asBoolean())
			.as("the route is still offered -- it is the code that waits")
			.isTrue();
		assertThat(last(desktop, "couplingCode")).isNull();
	}

	/**
	 * The bug this pins down: the incumbent sweep matched on {@code Subscriber.accountHash}, which
	 * {@code identify} sets for a connection that has not proved anything. A second, unauthenticated session
	 * claiming the same account used to receive the real incumbent's code.
	 */
	@Test
	void anUnauthenticatedSessionClaimingTheAccountDoesNotReceiveTheCode() throws Exception {
		identify(desktop, 4242L);
		clear(desktop);

		CollectingSession bystander = new CollectingSession("bystander");
		board.onOpen(bystander, "9.9.9.9");
		identify(bystander, 4242L); // claims the account; never authenticated

		identify(laptop, 4242L);
		requestCode(laptop, 4242L);

		assertThat(last(bystander, "couplingCode")).isNull();
	}

	@Test
	void confirmingTheRightCodeCouplesTheSecondDevice() throws Exception {
		identify(desktop, 4242L);
		clear(desktop);
		identify(laptop, 4242L);
		requestCode(laptop, 4242L);
		String code = last(desktop, "couplingCode").path("code").asText();

		confirm(laptop, 4242L, code);

		JsonNode issued = last(laptop, "authIssued");
		assertThat(issued).isNotNull();
		assertThat(issued.path("token").asText()).isNotBlank();
		assertThat(last(laptop, "couplingResult").path("success").asBoolean()).isTrue();
	}

	/** Coupling adds a machine. The one that displayed the code must keep working. */
	@Test
	void theFirstDeviceIsStillAuthenticatedAfterASecondCouples() throws Exception {
		identify(desktop, 4242L);
		String desktopToken = last(desktop, "authIssued").path("token").asText();
		clear(desktop);
		identify(laptop, 4242L);
		requestCode(laptop, 4242L);
		String code = last(desktop, "couplingCode").path("code").asText();

		confirm(laptop, 4242L, code);

		assertThat(auth.accountFor(desktopToken)).contains(4242L);
	}

	/**
	 * The bug this pins down: after confirming, the challenger is authenticated on the same account, so an
	 * unfiltered notice sweep reached it too and told it the credential it had just been given was gone.
	 */
	@Test
	void theConfirmingSessionIsNotToldItsOwnCouplingHappened() throws Exception {
		identify(desktop, 4242L);
		clear(desktop);
		identify(laptop, 4242L);
		requestCode(laptop, 4242L);
		String code = last(desktop, "couplingCode").path("code").asText();
		clear(desktop);

		confirm(laptop, 4242L, code);

		assertThat(last(laptop, "couplingAccepted")).isNull();
		assertThat(last(desktop, "couplingAccepted")).isNotNull();
	}

	@Test
	void aWrongCodeDoesNotCouple() throws Exception {
		identify(desktop, 4242L);
		identify(laptop, 4242L);

		confirm(laptop, 4242L, "000000");

		assertThat(last(laptop, "authIssued")).isNull();
		assertThat(last(laptop, "couplingResult").path("success").asBoolean()).isFalse();
	}

	/**
	 * An account can hold a credential with nobody currently connected on it -- the first device is offline.
	 * Told plainly rather than left waiting on a code nothing was ever there to display, and the pending
	 * request is dropped rather than left to block the next attempt for its whole TTL. The client still
	 * gets {@code authFailed}, with {@code coupling} false and whichever other routes back in -- recovery
	 * codes, Discord -- are actually open for this account.
	 */
	@Test
	void couplingWithNoIncumbentOnlineOffersOtherRoutes() throws Exception {
		// Enrolled directly, so the account has an active credential with no live session behind it.
		auth.enrol(4343L, null, null);

		identify(laptop, 4343L);

		JsonNode failed = last(laptop, "authFailed");
		assertThat(failed).isNotNull();
		assertThat(failed.path("coupling").asBoolean()).isFalse();
		assertThat(last(laptop, "authIssued")).isNull();
	}

	/**
	 * Ask for a code, which is now the only thing that mints one.
	 *
	 * <p>It used to happen automatically the moment a sign-in failed, which meant naming an account hash was
	 * enough to put a dialog on the screen of whoever really owned it. Every case below that wants a code
	 * therefore asks for one, and the ones that do not are checking that nothing arrives unbidden.
	 */
	private void requestCode(CollectingSession session, long accountHash) throws Exception {
		board.onMessage(session.id(),
			"{\"type\":\"requestCouplingCode\",\"accountHash\":" + accountHash + "}");
	}

	private void identify(CollectingSession session, long accountHash) throws Exception {
		// The board protocol keys its frame type as "type", not the "t" the live-party protocol uses --
		// two different wire shapes on two different sockets.
		board.onMessage(session.id(),
			"{\"type\":\"identify\",\"accountHash\":" + accountHash + "}");
	}

	private void confirm(CollectingSession session, long accountHash, String code) throws Exception {
		board.onMessage(session.id(),
			"{\"type\":\"couplingConfirm\",\"accountHash\":" + accountHash + ",\"code\":\"" + code + "\"}");
	}

	private void clear(CollectingSession session) {
		session.out.clear();
	}

	private JsonNode last(CollectingSession session, String type) throws Exception {
		// The board protocol keys every frame's type as "type" (see Outbound.type/Inbound.type), unlike the
		// live-party protocol's "t" -- two different wire shapes on two different sockets.
		JsonNode found = null;
		for (String json : session.out) {
			JsonNode node = mapper.readTree(json);
			if (type.equals(node.path("type").asText())) {
				found = node;
			}
		}
		return found;
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

package net.osparty.api.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.osparty.api.party.LocalPartyAdmissionService;
import net.osparty.api.repository.FakeAdvertisementRepository;
import net.osparty.api.repository.InMemoryAccountCredentialRepository;
import net.osparty.api.repository.InMemoryAccountRecoveryRepository;
import net.osparty.api.repository.InMemoryAdReportRepository;
import net.osparty.api.repository.InMemoryDiscordLinkRepository;
import net.osparty.api.service.AccountAuthService;
import net.osparty.api.service.DisabledAdReportService;
import net.osparty.api.service.DisabledVoiceChannelService;
import net.osparty.api.service.DiscordLinkService;
import net.osparty.api.service.LocalCouplingCodeStore;
import net.osparty.api.service.PlayerIdService;
import net.osparty.api.transport.SocketSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a client is actually told when it cannot be signed in, and what it can do about it.
 *
 * <p>The behaviour under test replaced a pair of frames that described a coupling attempt rather than the
 * thing that had happened. Two consequences drove the rewrite and are pinned here: a user whose device had
 * simply lost its credential was prompted for a six-digit code that had never been generated anywhere, and
 * because {@code identify} is re-sent on every reconnect, they were prompted again on every world hop and
 * every network blip. Both are wire-level properties -- neither is visible from the service alone, because
 * both are about which {@code Subscriber} is told what, and how often.
 */
class BoardBroadcasterRecoveryTest {
	private static final long ACCOUNT = 4242L;

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
		// A real link service, because handleStartDiscordLink checks isEnabled() before it checks ownership
		// and a null one would refuse for the wrong reason -- which would leave the gate below untested.
		// Its Redis handle stays null: a refused link never reaches a Redis call, and reaching one here
		// would mean the gate had already failed.
		DiscordLinkService links = new DiscordLinkService(null, mapper,
			new InMemoryDiscordLinkRepository(), "test-client", "https://osparty.test/callback");
		board = new BoardBroadcaster(new FakeAdvertisementRepository(), mapper,
			new DisabledVoiceChannelService(),
			links,
			null, // DiscordBadgeService: unreachable from these frames
			new LocalPresenceRegistry(),
			new LocalInviteBus(),
			new LocalCouplingBus(),
			null, // BanService: unreachable from these frames
			new LocalBoardChangeBus(),
			new LocalPartyAdmissionService(),
			auth,
			// DiscordRecoveryService: null is the "Discord not configured" build, which is what makes the
			// discord route report false below rather than needing Redis to say so.
			null,
			new PlayerIdService("test-salt"),
			true, true,
			new InMemoryAdReportRepository(),
			new DisabledAdReportService(),
			null, // ReportRateLimiter: unreachable from these frames, needs real Redis to build
			true, 5,
			new SimpleMeterRegistry());

		desktop = new CollectingSession("desktop");
		laptop = new CollectingSession("laptop");
		board.onOpen(desktop, "1.2.3.4");
		board.onOpen(laptop, "5.6.7.8");
	}

	/**
	 * The spam this whole frame exists to stop. A client that cannot enrol reconnects for reasons that have
	 * nothing to do with its account -- hopping worlds, losing wifi -- and re-sends {@code identify} each
	 * time. The answer has not changed, so it is not repeated.
	 */
	@Test
	void aFailedSignInIsExplainedOncePerConnection() throws Exception {
		auth.enrol(ACCOUNT, null, null);

		identify(laptop, ACCOUNT);
		int first = count(laptop, "authFailed");
		identify(laptop, ACCOUNT);
		identify(laptop, ACCOUNT);

		assertThat(first).isEqualTo(1);
		assertThat(count(laptop, "authFailed")).isEqualTo(1);
	}

	/**
	 * The way past that, and deliberately a user action rather than a timer: the usual reason somebody asks
	 * again is that they have just opened OSParty on the machine that holds the account.
	 */
	@Test
	void retryAuthAsksAgain() throws Exception {
		auth.enrol(ACCOUNT, null, null);
		identify(laptop, ACCOUNT);

		send(laptop, "{\"type\":\"retryAuth\",\"accountHash\":" + ACCOUNT + "}");

		assertThat(count(laptop, "authFailed")).isEqualTo(2);
	}

	/**
	 * Asking twice must not talk the user out of the code they already have.
	 *
	 * <p>Minting refuses while one is pending, and the obvious reading of that refusal is "nothing sent" --
	 * exactly backwards for the session the pending code belongs to, since it is on the other screen right
	 * now waiting to be typed. Nor may a second code land there: a user who clicks twice would otherwise
	 * invalidate the number they are in the middle of reading.
	 */
	@Test
	void askingTwiceKeepsTheCodeAlreadyWaiting() throws Exception {
		identify(desktop, ACCOUNT);
		identify(laptop, ACCOUNT);
		requestCode(laptop, ACCOUNT);
		String first = last(desktop, "couplingCode").path("code").asText();
		clear(desktop);

		requestCode(laptop, ACCOUNT);

		assertThat(last(laptop, "couplingCodeSent").path("reached").asInt())
			.as("the pending code still counts as sent")
			.isEqualTo(1);
		assertThat(last(desktop, "couplingCode"))
			.as("the other device must not be shown a second code")
			.isNull();
		send(laptop, "{\"type\":\"couplingConfirm\",\"accountHash\":" + ACCOUNT
			+ ",\"code\":\"" + first + "\"}");
		assertThat(last(laptop, "authIssued")).isNotNull();
	}

	/**
	 * Presence, not a minted code. The distinction is the whole of the second fix: {@code coupling} says the
	 * route exists, and nothing appears on anybody's screen until it is asked for.
	 */
	@Test
	void retryAuthDoesNotMintACode() throws Exception {
		identify(desktop, ACCOUNT);
		identify(laptop, ACCOUNT);
		clear(desktop);

		send(laptop, "{\"type\":\"retryAuth\",\"accountHash\":" + ACCOUNT + "}");

		assertThat(last(laptop, "authFailed").path("coupling").asBoolean()).isTrue();
		assertThat(last(desktop, "couplingCode")).isNull();
	}

	/**
	 * The case that was being described as a coupling problem. Nothing is online to show a code, so offering
	 * to check one would be offering a door that cannot open -- but the account does have recovery codes,
	 * minted with its first device, and saying so is the entire point of the frame.
	 */
	@Test
	void withNoOtherDeviceOnlineTheOfferIsRecoveryCodesNotACode() throws Exception {
		auth.enrol(ACCOUNT, null, null);

		identify(laptop, ACCOUNT);

		JsonNode failed = last(laptop, "authFailed");
		assertThat(failed).isNotNull();
		assertThat(failed.path("reason").asText()).isEqualTo("already-enrolled");
		assertThat(failed.path("coupling").asBoolean()).isFalse();
		assertThat(failed.path("recoveryCodes").asBoolean()).isTrue();
		assertThat(failed.path("discord").asBoolean()).isFalse();
	}

	/** With a signed-in machine there to display one, the code route is real and is offered. */
	@Test
	void withAnotherDeviceOnlineTheCodeRouteIsOffered() throws Exception {
		identify(desktop, ACCOUNT);
		clear(desktop);

		identify(laptop, ACCOUNT);
		requestCode(laptop, ACCOUNT);

		assertThat(last(laptop, "authFailed").path("coupling").asBoolean()).isTrue();
		assertThat(last(desktop, "couplingCode").path("code").asText()).matches("\\d{6}");
	}

	/** The frame goes to a connection that has proved nothing, so it must give nothing away. */
	@Test
	void theFailureFrameCarriesNoSecret() throws Exception {
		identify(desktop, ACCOUNT);
		clear(desktop);

		identify(laptop, ACCOUNT);

		JsonNode failed = last(laptop, "authFailed");
		assertThat(failed.has("code")).isFalse();
		assertThat(failed.has("token")).isFalse();
	}

	@Test
	void aRecoveryCodeSignsInADeviceThatHasNothingElse() throws Exception {
		String code = auth.enrol(ACCOUNT, null, null).orElseThrow().recoveryCodes().get(0);

		recoveryConfirm(laptop, ACCOUNT, code);

		assertThat(last(laptop, "recoveryResult").path("success").asBoolean()).isTrue();
		assertThat(last(laptop, "authIssued").path("token").asText()).isNotBlank();
	}

	/**
	 * A recovered device is authenticated for the rest of the connection, not merely handed a token to use
	 * next time. Otherwise the user recovers and still cannot host until they reconnect.
	 */
	@Test
	void aRecoveredSessionCanImmediatelyActAsTheAccount() throws Exception {
		String code = auth.enrol(ACCOUNT, null, null).orElseThrow().recoveryCodes().get(0);
		recoveryConfirm(laptop, ACCOUNT, code);
		clear(laptop);

		send(laptop, "{\"type\":\"listDevices\"}");

		assertThat(last(laptop, "devices")).isNotNull();
		assertThat(last(laptop, "error")).isNull();
	}

	@Test
	void aWrongRecoveryCodeSignsInNothing() throws Exception {
		auth.enrol(ACCOUNT, null, null);

		recoveryConfirm(laptop, ACCOUNT, "ZZZZ-ZZZZ-ZZZZ-ZZZZ");

		assertThat(last(laptop, "recoveryResult").path("success").asBoolean()).isFalse();
		assertThat(last(laptop, "authIssued")).isNull();
	}

	/**
	 * Eighty bits is what makes guessing hopeless; this ceiling is what stops a client looping. It is per
	 * connection because that is the only scope there is before a caller has proved anything.
	 */
	@Test
	void aConnectionGrindingThroughCodesIsCutOff() throws Exception {
		auth.enrol(ACCOUNT, null, null);

		for (int attempt = 0; attempt < 12; attempt++) {
			recoveryConfirm(laptop, ACCOUNT, "ZZZZ-ZZZZ-ZZZZ-ZZZ" + attempt);
		}

		assertThat(last(laptop, "recoveryResult").path("detail").asText()).contains("too many attempts");
	}

	/** Codes are the account's own business: there is deliberately no way to ask for another account's. */
	@Test
	void anUnauthenticatedSessionCannotIssueRecoveryCodes() throws Exception {
		auth.enrol(ACCOUNT, null, null);
		identify(laptop, ACCOUNT); // claims the account; never proves it
		clear(laptop);

		send(laptop, "{\"type\":\"issueRecoveryCodes\"}");

		assertThat(last(laptop, "recoveryCodes")).isNull();
		assertThat(last(laptop, "error")).isNotNull();
	}

	@Test
	void aSignedInSessionCanReplaceItsOwnCodes() throws Exception {
		identify(desktop, ACCOUNT);
		clear(desktop);

		send(desktop, "{\"type\":\"issueRecoveryCodes\"}");

		JsonNode codes = last(desktop, "recoveryCodes");
		assertThat(codes.path("codes")).hasSize(10);
		assertThat(codes.path("remaining").asInt()).isEqualTo(10);
	}

	/** A status check answers with the count and never with the codes, which are shown once and only once. */
	@Test
	void aStatusCheckDoesNotHandBackTheCodes() throws Exception {
		identify(desktop, ACCOUNT);
		clear(desktop);

		send(desktop, "{\"type\":\"recoveryStatus\"}");

		JsonNode codes = last(desktop, "recoveryCodes");
		assertThat(codes.path("remaining").asInt()).isEqualTo(10);
		assertThat(codes.has("codes")).isFalse();
	}

	/**
	 * The first device on an account is handed its codes with the credential, because that is the only
	 * moment they will ever be visible and the only moment the account has exactly one way back in.
	 */
	@Test
	void anAccountsFirstDeviceIsGivenItsCodesAtOnce() throws Exception {
		identify(desktop, ACCOUNT);

		JsonNode issued = last(desktop, "authIssued");
		assertThat(issued.path("firstDevice").asBoolean()).isTrue();
		assertThat(issued.path("codes")).hasSize(10);
	}

	/**
	 * The name is the test. A client deserialises every frame into one flat shape, so this list must not
	 * share a name with {@code authFailed}'s {@code recoveryCodes} boolean -- when it did, the plugin's Gson
	 * threw binding an array to a Boolean and dropped the entire frame, which meant a first sign-in stored no
	 * credential and displayed no codes while the server had already written the row. Nothing on either side
	 * failed loudly; it simply did nothing.
	 */
	@Test
	void theIssuedCodesDoNotCollideWithTheAuthFailedFlag() throws Exception {
		identify(desktop, ACCOUNT);
		JsonNode issued = last(desktop, "authIssued");
		identify(laptop, ACCOUNT);
		JsonNode failed = last(laptop, "authFailed");

		assertThat(issued.path("codes").isArray()).isTrue();
		assertThat(issued.has("recoveryCodes")).isFalse();
		assertThat(failed.path("recoveryCodes").isBoolean()).isTrue();
		assertThat(failed.has("codes")).isFalse();
	}

	/**
	 * Every later enrolment must not carry them: the account already has a set, and re-issuing here would
	 * invalidate the sheet the user is holding at the moment they proved they still need it.
	 */
	@Test
	void aCoupledDeviceIsNotGivenAFreshSheet() throws Exception {
		identify(desktop, ACCOUNT);
		clear(desktop);
		identify(laptop, ACCOUNT);
		requestCode(laptop, ACCOUNT);
		String code = last(desktop, "couplingCode").path("code").asText();

		send(laptop, "{\"type\":\"couplingConfirm\",\"accountHash\":" + ACCOUNT
			+ ",\"code\":\"" + code + "\"}");

		JsonNode issued = last(laptop, "authIssued");
		assertThat(issued.path("firstDevice").asBoolean()).isFalse();
		assertThat(issued.has("codes")).isFalse();
	}

	/**
	 * The prerequisite that keeps Discord recovery from being circular. Linking used to accept any account
	 * hash from a session that had merely named it, so an attacker could bind their own Discord account to
	 * somebody else's hash and then "recover" it. A session that has not proved the account cannot link it.
	 */
	@Test
	void anUnauthenticatedSessionCannotLinkDiscordToAnEnrolledAccount() throws Exception {
		auth.enrol(ACCOUNT, null, null);
		identify(laptop, ACCOUNT);
		clear(laptop);

		send(laptop, "{\"type\":\"startDiscordLink\",\"accountHash\":" + ACCOUNT + "}");

		assertThat(last(laptop, "discordLinkUrl")).isNull();
		assertThat(last(laptop, "error")).isNotNull();
	}

	/** Unlinking is the same bar: it is how a verified link would be cleared before planting a new one. */
	@Test
	void anUnauthenticatedSessionCannotUnlinkDiscordFromAnEnrolledAccount() throws Exception {
		auth.enrol(ACCOUNT, null, null);
		identify(laptop, ACCOUNT);
		clear(laptop);

		send(laptop, "{\"type\":\"unlinkDiscord\",\"accountHash\":" + ACCOUNT + "}");

		assertThat(last(laptop, "discordLink")).isNull();
		assertThat(last(laptop, "error")).isNotNull();
	}

	private void identify(CollectingSession session, long accountHash) {
		send(session, "{\"type\":\"identify\",\"accountHash\":" + accountHash + "}");
	}

	/** Asking for a code, which since the deferred-code change is the only thing that mints one. */
	private void requestCode(CollectingSession session, long accountHash) {
		send(session, "{\"type\":\"requestCouplingCode\",\"accountHash\":" + accountHash + "}");
	}

	private void recoveryConfirm(CollectingSession session, long accountHash, String code) {
		send(session, "{\"type\":\"recoveryConfirm\",\"accountHash\":" + accountHash
			+ ",\"code\":\"" + code + "\"}");
	}

	private void send(CollectingSession session, String frame) {
		board.onMessage(session.id(), frame);
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

	private int count(CollectingSession session, String type) throws Exception {
		int seen = 0;
		for (String json : session.out) {
			if (type.equals(mapper.readTree(json).path("type").asText())) {
				seen++;
			}
		}
		return seen;
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

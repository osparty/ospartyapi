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
 * Listing and revoking devices, as it runs through {@link BoardBroadcaster}. What matters here is the same
 * thing {@link BoardBroadcasterCouplingTest} checks for coupling: that ownership is enforced by which
 * {@code Subscriber} sent the frame, not by what the frame claims about itself. A frame naming someone else's
 * account hash must not be able to list or revoke that account's devices.
 */
class BoardBroadcasterDeviceTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private AccountAuthService auth;
	private BoardBroadcaster board;
	private CollectingSession session;

	@BeforeEach
	void setUp() {
		auth = new AccountAuthService(new InMemoryAccountCredentialRepository(),
			new LocalCouplingCodeStore(), true);
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		board = new BoardBroadcaster(new FakeAdvertisementRepository(), mapper,
			new DisabledVoiceChannelService(),
			null, null, // DiscordLinkService, DiscordBadgeService: unreachable from these frames
			new LocalPresenceRegistry(),
			new LocalInviteBus(),
			null, // BanService: unreachable from these frames
			new LocalBoardChangeBus(),
			new LocalPartyAdmissionService(),
			auth,
			new PlayerIdService("test-salt"),
			true, true,
			new InMemoryAdReportRepository(),
			new DisabledAdReportService(),
			null, // ReportRateLimiter: unreachable from these frames, needs real Redis to build
			true, 5,
			meters);

		session = new CollectingSession("session");
		board.onOpen(session, "1.2.3.4", 4242L); // pre-authenticated, as an already-issued credential would be
	}

	@Test
	void anUnauthenticatedSessionCannotListDevices() throws Exception {
		CollectingSession stranger = new CollectingSession("stranger");
		board.onOpen(stranger, "9.9.9.9");

		send(stranger, "{\"type\":\"listDevices\"}");

		assertThat(last(stranger, "devices")).isNull();
		assertThat(last(stranger, "error")).isNotNull();
	}

	@Test
	void anAuthenticatedSessionListsItsOwnDevice() throws Exception {
		auth.enrol(4242L, null, null);

		send(session, "{\"type\":\"listDevices\"}");

		JsonNode devices = last(session, "devices");
		assertThat(devices).isNotNull();
		assertThat(devices.path("devices")).hasSize(1);
	}

	@Test
	void anUnauthenticatedSessionCannotRevokeADevice() throws Exception {
		auth.enrol(4242L, null, null);
		String deviceId = auth.devices(4242L).get(0).tokenHash();
		CollectingSession stranger = new CollectingSession("stranger");
		board.onOpen(stranger, "9.9.9.9");

		send(stranger, "{\"type\":\"revokeDevice\",\"deviceId\":\"" + deviceId + "\"}");

		assertThat(last(stranger, "deviceRevoked")).isNull();
		assertThat(auth.devices(4242L)).hasSize(1); // untouched
	}

	/**
	 * The bug this shape exists to prevent: a frame naming a foreign account hash must not let one account
	 * revoke another's device. Ownership here comes only from the authenticated session's own accountHash.
	 */
	@Test
	void aSessionCannotRevokeAnotherAccountsDeviceByNamingItsHash() throws Exception {
		auth.enrol(9999L, null, null);
		String otherDeviceId = auth.devices(9999L).get(0).tokenHash();

		// session is authenticated as 4242; the frame tries to act on 9999's device anyway.
		send(session, "{\"type\":\"revokeDevice\",\"accountHash\":9999,\"deviceId\":\"" + otherDeviceId + "\"}");

		JsonNode result = last(session, "deviceRevoked");
		assertThat(result).isNotNull();
		assertThat(result.path("success").asBoolean()).isFalse();
		assertThat(auth.devices(9999L)).hasSize(1); // untouched
	}

	@Test
	void anAuthenticatedSessionRevokesItsOwnDeviceById() throws Exception {
		auth.enrol(4242L, null, null);
		String deviceId = auth.devices(4242L).get(0).tokenHash();

		send(session, "{\"type\":\"revokeDevice\",\"deviceId\":\"" + deviceId + "\"}");

		JsonNode result = last(session, "deviceRevoked");
		assertThat(result.path("success").asBoolean()).isTrue();
		assertThat(result.path("deviceId").asText()).isEqualTo(deviceId);
		assertThat(auth.devices(4242L)).isEmpty();
	}

	@Test
	void anUnauthenticatedSessionCannotRenameADevice() throws Exception {
		auth.enrol(4242L, null, "Desktop");
		String deviceId = auth.devices(4242L).get(0).tokenHash();
		CollectingSession stranger = new CollectingSession("stranger");
		board.onOpen(stranger, "9.9.9.9");

		send(stranger, "{\"type\":\"renameDevice\",\"deviceId\":\"" + deviceId + "\",\"label\":\"Not mine\"}");

		assertThat(last(stranger, "deviceRenamed")).isNull();
		assertThat(auth.devices(4242L)).singleElement()
			.satisfies(d -> assertThat(d.label()).isEqualTo("Desktop")); // untouched
	}

	@Test
	void aSessionCannotRenameAnotherAccountsDeviceByNamingItsHash() throws Exception {
		auth.enrol(9999L, null, "Other's PC");
		String otherDeviceId = auth.devices(9999L).get(0).tokenHash();

		send(session, "{\"type\":\"renameDevice\",\"accountHash\":9999,\"deviceId\":\"" + otherDeviceId
			+ "\",\"label\":\"Mine now\"}");

		JsonNode result = last(session, "deviceRenamed");
		assertThat(result.path("success").asBoolean()).isFalse();
		assertThat(auth.devices(9999L)).singleElement()
			.satisfies(d -> assertThat(d.label()).isEqualTo("Other's PC")); // untouched
	}

	@Test
	void anAuthenticatedSessionRenamesItsOwnDevice() throws Exception {
		auth.enrol(4242L, null, "Desktop");
		String deviceId = auth.devices(4242L).get(0).tokenHash();

		send(session, "{\"type\":\"renameDevice\",\"deviceId\":\"" + deviceId + "\",\"label\":\"Home PC\"}");

		JsonNode result = last(session, "deviceRenamed");
		assertThat(result.path("success").asBoolean()).isTrue();
		assertThat(result.path("deviceId").asText()).isEqualTo(deviceId);
		assertThat(auth.devices(4242L)).singleElement()
			.satisfies(d -> assertThat(d.label()).isEqualTo("Home PC"));
	}

	private void send(CollectingSession s, String json) throws Exception {
		board.onMessage(s.id(), json);
	}

	private JsonNode last(CollectingSession s, String type) throws Exception {
		JsonNode found = null;
		for (String json : s.out) {
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

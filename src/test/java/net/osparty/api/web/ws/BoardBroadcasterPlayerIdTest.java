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
 * The end-to-end proof for WEBSOCKET_AUTH_RESEARCH.md §10.3: a board ad's member list now carries
 * {@code playerId} on the actual frames a client receives, not just in {@link PlayerIdService} unit tests.
 * {@link net.osparty.api.service.AccountAuthTest#enrichAdsStampsAPlayerIdOnEachMemberWithAnAccount} pins the
 * enrichment logic; this pins that {@link BoardBroadcaster} actually calls it on both paths a member list can
 * reach a subscriber -- the host's own {@code hosted} ack, and a fresh subscriber's {@code snapshot}.
 */
class BoardBroadcasterPlayerIdTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private PlayerIdService playerIds;
	private BoardBroadcaster board;

	@BeforeEach
	void setUp() {
		playerIds = new PlayerIdService("test-salt");
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		// Unlike identify/couplingConfirm/listDevices/revokeDevice in the other BoardBroadcaster test
		// files, handleHost and subscribe both run badge enrichment for real -- so it needs an instance
		// that will not NPE, not null. A null StringRedisTemplate is fine: DiscordBadgeService.enrichAds
		// wraps its Redis lookup in its own try/catch and degrades to "no badges" on any failure, which is
		// exactly the behaviour this relies on rather than works around.
		net.osparty.api.service.DiscordLinkService links =
			new net.osparty.api.service.DiscordLinkService(null, mapper, null, "", "");
		net.osparty.api.service.DiscordBadgeService badges =
			new net.osparty.api.service.DiscordBadgeService(null, mapper, links, null);
		// isHidden reads an in-memory snapshot that starts empty; nothing here ever touches the repository.
		net.osparty.api.service.BanService bans =
			new net.osparty.api.service.BanService(null, meters);
		board = new BoardBroadcaster(new FakeAdvertisementRepository(), mapper,
			new DisabledVoiceChannelService(),
			null, badges, // DiscordLinkService: unreachable from host/subscribe
			new LocalPresenceRegistry(),
			new LocalInviteBus(),
			bans,
			new LocalBoardChangeBus(),
			new LocalPartyAdmissionService(),
			new AccountAuthService(new InMemoryAccountCredentialRepository(),
				new LocalCouplingCodeStore(), true),
			playerIds,
			true, true,
			new InMemoryAdReportRepository(),
			new DisabledAdReportService(),
			null, // ReportRateLimiter: unreachable from host/subscribe, needs real Redis to build
			true, 5,
			meters);
	}

	@Test
	void theHostsOwnAckCarriesAPlayerIdForTheHost() throws Exception {
		CollectingSession host = new CollectingSession("host");
		board.onOpen(host, "1.2.3.4");

		host.out.clear();
		board.onMessage(host.id(), "{\"type\":\"host\",\"request\":{"
			+ "\"host\":\"Alice\",\"hostAccountHash\":4242,\"activity\":\"cox\",\"capacity\":5}}");

		JsonNode member = last(host, "hosted").path("ad").path("members").get(0);
		assertThat(member.path("accountHash").asLong()).isEqualTo(4242L);
		assertThat(member.path("playerId").asText()).isEqualTo(playerIds.of(4242L));
	}

	@Test
	void aFreshSubscribersSnapshotCarriesPlayerIdsToo() throws Exception {
		CollectingSession host = new CollectingSession("host");
		board.onOpen(host, "1.2.3.4");
		board.onMessage(host.id(), "{\"type\":\"host\",\"request\":{"
			+ "\"host\":\"Alice\",\"hostAccountHash\":4242,\"activity\":\"cox\",\"capacity\":5}}");

		CollectingSession watcher = new CollectingSession("watcher");
		board.onOpen(watcher, "5.6.7.8");
		board.onMessage(watcher.id(), "{\"type\":\"subscribe\"}");

		JsonNode ads = last(watcher, "snapshot").path("ads");
		assertThat(ads).hasSize(1);
		assertThat(ads.get(0).path("members").get(0).path("playerId").asText())
			.isEqualTo(playerIds.of(4242L));
	}

	private void send(CollectingSession session, String json) throws Exception {
		board.onMessage(session.id(), json);
	}

	private JsonNode last(CollectingSession session, String type) throws Exception {
		JsonNode found = null;
		for (String json : session.out) {
			JsonNode node = mapper.readTree(json);
			if (type.equals(node.path("type").asText())) {
				found = node;
			}
		}
		if (found == null) {
			throw new AssertionError("no " + type + " frame reached " + session.id() + ": " + session.out);
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

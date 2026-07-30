package net.osparty.api.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.osparty.api.model.Party;
import net.osparty.api.repository.InMemoryBanRepository;
import net.osparty.api.service.BanService;
import net.osparty.api.service.VoiceChannelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

/**
 * End-to-end behaviour of the shadowban over the live socket, with two clients: {@code A} hosts the
 * advertisement and gets banned, {@code B} is everybody else.
 *
 * <p>The assertions that matter most are the negative ones. A shadowban that is merely *effective*
 * is easy; a shadowban that is also *silent* is the hard part, and every way this feature could
 * announce itself to its own subject has a case below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"app.ws.reconcile-interval-ms=150",
	"app.bans.refresh-ms=100",
	"app.bans.filter-get-by-host=true",
	"app.bans.filter-get-by-code=true",
	// Every WebSocket is served by Netty on its own port, not by the servlet container's. Zero takes an
	// ephemeral one so the whole suite can run without colliding on 8081.
	"app.socket.port=0"})
class AdShadowbanSocketTest {
	private static final long RECONCILE_MS = 150;

	@Autowired
	private net.osparty.api.party.netty.NettySocketServer socketServer;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private InMemoryBanRepository banRepository;

	@Autowired
	private BanService bans;

	@Autowired
	private StubVoiceChannelService voice;

	@TestConfiguration
	static class Config {
		@Bean
		@Primary
		StubVoiceChannelService stubVoiceChannelService() {
			return new StubVoiceChannelService();
		}
	}

	@AfterEach
	void clearBans() {
		banRepository.clear();
		bans.refresh();
		voice.deleted.set(null);
	}

	@Test
	void banHidesTheAdFromOthersButNotFromItsOwnHost() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		BlockingQueue<JsonNode> b = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		WebSocketSession viewerSession = connect(b);
		try {
			subscribe(hostSession, a);
			subscribe(viewerSession, b);
			String id = host(hostSession, a, "BanSubject", "k-ban");
			awaitCreated(b, id);

			ban("BanSubject");

			// Everyone else is told the ad is gone...
			awaitWhere(b, m -> "batch".equals(type(m)) && contains(m.path("removed"), id),
				"removed for the viewer");
			// ...and the host is told nothing at all. Drained well past several reconcile ticks.
			assertNoFrame(a, m -> "batch".equals(type(m)) && contains(m.path("removed"), id),
				RECONCILE_MS * 6);
		}
		finally {
			close(hostSession, viewerSession);
		}
	}

	/**
	 * The plugin polls its own ad by host name on every heartbeat from the Party tab, and treats a
	 * null answer as "the server dropped my party": it leaves the live room, clears the tab and
	 * tells the user their party was removed. If the self-check here ever regresses, the ban stops
	 * being silent and starts disbanding its subject's party in front of them.
	 */
	@Test
	void bannedHostCanStillLookUpTheirOwnAdByHost() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		try {
			subscribe(hostSession, a);
			host(hostSession, a, "SelfLookup", "k-self");
			ban("SelfLookup");

			send(hostSession, "{\"type\":\"getByHost\",\"host\":\"SelfLookup\"}");
			JsonNode found = awaitWhere(a, m -> "byHost".equals(type(m)), "byHost for self");
			assertThat(found.path("party").path("host").asText()).isEqualTo("SelfLookup");
		}
		finally {
			close(hostSession);
		}
	}

	/**
	 * Same lookup, but from a session that has never hosted and never identified — so neither the
	 * server-side ownership binding nor the self-reported identity applies, and the ad must be
	 * withheld.
	 */
	@Test
	void othersCannotLookUpABannedAdByHostOrCode() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		BlockingQueue<JsonNode> b = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		WebSocketSession viewerSession = connect(b);
		try {
			subscribe(hostSession, a);
			JsonNode hosted = hostFrame(hostSession, a, "HiddenFromLookup", "k-lookup");
			String code = hosted.path("party").path("inviteCode").asText();
			assertThat(code).isNotBlank();
			ban("HiddenFromLookup");

			send(viewerSession, "{\"type\":\"getByHost\",\"host\":\"HiddenFromLookup\"}");
			JsonNode byHost = awaitWhere(b, m -> "byHost".equals(type(m)), "byHost for a stranger");
			assertThat(byHost.has("party")).isFalse();

			send(viewerSession, "{\"type\":\"getByCode\",\"code\":\"" + code + "\"}");
			JsonNode byCode = awaitWhere(b, m -> "byCode".equals(type(m)), "byCode for a stranger");
			assertThat(byCode.has("party")).isFalse();
		}
		finally {
			close(hostSession, viewerSession);
		}
	}

	@Test
	void bannedAdIsAbsentFromASnapshotForOthersAndPresentForItsHost() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		try {
			subscribe(hostSession, a);
			String id = host(hostSession, a, "SnapshotSubject", "k-snap");
			ban("SnapshotSubject");

			// A fresh subscriber sees a board without the ad.
			BlockingQueue<JsonNode> b = new LinkedBlockingQueue<>();
			WebSocketSession viewerSession = connect(b);
			try {
				JsonNode strangerSnapshot = subscribe(viewerSession, b);
				assertThat(contains(strangerSnapshot.path("parties"), "id", id)).isFalse();

				// The host re-subscribing still sees their own.
				send(hostSession, "{\"type\":\"subscribe\"}");
				JsonNode ownSnapshot = awaitWhere(a, m -> "snapshot".equals(type(m)), "own snapshot");
				assertThat(contains(ownSnapshot.path("parties"), "id", id)).isTrue();
			}
			finally {
				close(viewerSession);
			}
		}
		finally {
			close(hostSession);
		}
	}

	@Test
	void bannedHostsInvitesAreDroppedButAcknowledgedAsDelivered() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		BlockingQueue<JsonNode> b = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		WebSocketSession targetSession = connect(b);
		try {
			subscribe(hostSession, a);
			send(targetSession, "{\"type\":\"identify\",\"accountHash\":991,\"name\":\"InviteTarget\"}");
			String id = host(hostSession, a, "InviteBanned", "k-inv");
			ban("InviteBanned");

			send(hostSession, "{\"type\":\"invite\",\"id\":\"" + id
				+ "\",\"name\":\"InviteBanned\",\"target\":\"InviteTarget\"}");

			JsonNode ack = awaitWhere(a, m -> "inviteAck".equals(type(m)), "invite ack");
			// Claims success, so the sender learns nothing.
			assertThat(ack.path("delivered").asBoolean()).isTrue();
			// But nothing actually arrives.
			assertNoFrame(b, m -> "invited".equals(type(m)), 1000);
		}
		finally {
			close(hostSession, targetSession);
		}
	}

	@Test
	void unbanRepublishesTheAdToEveryone() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		BlockingQueue<JsonNode> b = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		WebSocketSession viewerSession = connect(b);
		try {
			subscribe(hostSession, a);
			subscribe(viewerSession, b);
			String id = host(hostSession, a, "Rehabilitated", "k-unban");
			awaitCreated(b, id);

			ban("Rehabilitated");
			awaitWhere(b, m -> "batch".equals(type(m)) && contains(m.path("removed"), id), "removed");

			bans.unban("Rehabilitated", 0, "appealed", "1", "mod");
			awaitCreated(b, id);
		}
		finally {
			close(hostSession, viewerSession);
		}
	}

	/**
	 * A ban hides a record; it does not destroy one. Tearing down the Discord voice channel would
	 * eject the host and their party from a call they are actively in — about as loud a signal as
	 * this feature could possibly send.
	 */
	@Test
	void banDoesNotTearDownTheDiscordVoiceChannel() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		try {
			subscribe(hostSession, a);
			String id = host(hostSession, a, "VoiceKeeper", "k-voice");
			send(hostSession, "{\"type\":\"createVoiceChannel\",\"id\":\"" + id + "\",\"key\":\"k-voice\"}");
			awaitWhere(a, m -> "voiceChannel".equals(type(m)), "voice channel created");

			ban("VoiceKeeper");
			letReconcilerRun(RECONCILE_MS * 6);
			assertThat(voice.deleted.get()).as("voice channel must survive a ban").isNull();

			// ...but a genuine disband still collects it.
			send(hostSession, "{\"type\":\"unhost\",\"id\":\"" + id + "\",\"key\":\"k-voice\"}");
			awaitCondition(() -> voice.deleted.get() != null, "voice channel deleted on unhost");
		}
		finally {
			close(hostSession);
		}
	}

	/**
	 * Regression for a bug that predates bans: flipping an ad to private removes it from the public
	 * list, which the old single-axis reconciler read as "gone" and acted on by deleting the live
	 * voice channel.
	 */
	@Test
	void goingPrivateDoesNotTearDownTheDiscordVoiceChannel() throws Exception {
		BlockingQueue<JsonNode> a = new LinkedBlockingQueue<>();
		WebSocketSession hostSession = connect(a);
		try {
			subscribe(hostSession, a);
			String id = host(hostSession, a, "GoesPrivate", "k-priv");
			send(hostSession, "{\"type\":\"createVoiceChannel\",\"id\":\"" + id + "\",\"key\":\"k-priv\"}");
			awaitWhere(a, m -> "voiceChannel".equals(type(m)), "voice channel created");

			send(hostSession, "{\"type\":\"update\",\"id\":\"" + id
				+ "\",\"key\":\"k-priv\",\"patch\":{\"privateParty\":true}}");
			letReconcilerRun(RECONCILE_MS * 6);
			assertThat(voice.deleted.get()).as("voice channel must survive going private").isNull();
		}
		finally {
			close(hostSession);
		}
	}

	/** A banned player who merely joins someone else's party must not drag that host down with them. */
	@Test
	void banOnANonHostMemberDoesNotHideTheParty() {
		Party party = new Party();
		party.setId("p-innocent");
		party.setHost("InnocentHost");
		party.setHostAccountHash(1L);
		party.setMembers(java.util.List.of(
			new net.osparty.api.model.Member("InnocentHost", 1L),
			new net.osparty.api.model.Member("BannedJoiner", 2L)));

		bans.ban("BannedJoiner", 2L, "spam", "1", "mod", null);

		assertThat(bans.isHidden(party)).isFalse();
	}

	@Test
	void banMatchesTheAccountHashEvenAfterARename() {
		Party renamed = new Party();
		renamed.setId("p-renamed");
		renamed.setHost("BrandNewName");
		renamed.setHostAccountHash(4242L);

		bans.ban("OldName", 4242L, "spam", "1", "mod", null);

		assertThat(bans.isHidden(renamed)).isTrue();
	}

	// --- helpers -------------------------------------------------------------------------------

	private void ban(String host) {
		bans.ban(host, 0, "boosting adverts", "1", "mod", null);
	}

	private WebSocketSession connect(BlockingQueue<JsonNode> messages) throws Exception {
		return BoardChannel.connect(socketServer.boundPort(), mapper, messages);
	}

	/** Subscribes and returns the snapshot frame that subscribing produces. */
	private JsonNode subscribe(WebSocketSession session, BlockingQueue<JsonNode> messages) throws Exception {
		send(session, "{\"type\":\"subscribe\"}");
		return awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");
	}

	private JsonNode hostFrame(WebSocketSession session, BlockingQueue<JsonNode> messages,
		String host, String key) throws Exception {
		send(session, "{\"type\":\"host\",\"key\":\"" + key + "\",\"request\":"
			+ "{\"activity\":\"cox\",\"host\":\"" + host + "\",\"capacity\":3,"
			+ "\"passphrase\":\"pp-" + key + "\"}}");
		return awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack for " + host);
	}

	private String host(WebSocketSession session, BlockingQueue<JsonNode> messages,
		String host, String key) throws Exception {
		return hostFrame(session, messages, host, key).path("party").path("id").asText();
	}

	private static void send(WebSocketSession session, String json) throws Exception {
		BoardChannel.send(session, json);
	}

	private static void close(WebSocketSession... sessions) throws Exception {
		for (WebSocketSession session : sessions) {
			session.close();
		}
	}

	private void awaitCreated(BlockingQueue<JsonNode> messages, String id) {
		awaitWhere(messages,
			m -> "batch".equals(type(m)) && contains(m.path("created"), "id", id),
			"created for " + id);
	}

	/** Gives the reconciler several ticks to do whatever it was going to do. */
	private static void letReconcilerRun(long windowMs) {
		assertNoFrame(new LinkedBlockingQueue<>(), m -> false, windowMs);
	}

	/** Fails if any frame matching {@code unwanted} arrives within the window. */
	private static void assertNoFrame(BlockingQueue<JsonNode> messages, Predicate<JsonNode> unwanted,
		long windowMs) {
		long deadline = System.currentTimeMillis() + windowMs;
		while (System.currentTimeMillis() < deadline) {
			JsonNode msg;
			try {
				msg = messages.poll(50, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted while draining", e);
			}
			if (msg != null && unwanted.test(msg)) {
				throw new AssertionError("Unexpected frame: " + msg);
			}
		}
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

	private static String type(JsonNode msg) {
		return msg.path("type").asText();
	}

	/** Whether an array of plain string ids contains {@code id}. */
	private static boolean contains(JsonNode array, String id) {
		if (!array.isArray()) {
			return false;
		}
		for (JsonNode node : array) {
			if (id.equals(node.asText())) {
				return true;
			}
		}
		return false;
	}

	/** Whether an array of objects contains one whose {@code field} equals {@code value}. */
	private static boolean contains(JsonNode array, String field, String value) {
		if (!array.isArray()) {
			return false;
		}
		for (JsonNode node : array) {
			if (value.equals(node.path(field).asText())) {
				return true;
			}
		}
		return false;
	}

	static class StubVoiceChannelService implements VoiceChannelService {
		final AtomicReference<String> deleted = new AtomicReference<>();

		@Override
		public Optional<VoiceChannelInfo> createForParty(Party party,
			java.util.Collection<String> allowedDiscordIds) {
			return Optional.of(new VoiceChannelInfo("chan-" + party.getId(),
				"https://discord.gg/" + party.getId()));
		}

		@Override
		public void rename(String channelId, Party party) {
		}

		@Override
		public void delete(String channelId) {
			deleted.set(channelId);
		}

		@Override
		public boolean grantAccess(String channelId, String discordId) {
			return true;
		}

		@Override
		public void revokeAccess(String channelId, String discordId) {
		}

		@Override
		public void disconnectFromChannel(String channelId, String discordId) {
		}
	}
}

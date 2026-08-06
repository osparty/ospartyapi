package net.osparty.api.party;

import net.osparty.api.transport.Mux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.osparty.api.party.netty.NettySocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * The Netty transport over real sockets: a real handshake, real frames, and the close callback that removes
 * a member. The protocol itself is covered by {@link PartyProtocolTest} — what is under test here is only
 * that this transport reaches the same frame handler, on both the plain and the node-hinted path, and that
 * it refuses everything else.
 *
 * <p>In this package rather than beside the server because it drives the manager's flush directly, which is
 * internal to the room layer.
 */
class NettySocketServerTest {
	private static final long MEMBER_TIMEOUT_MS = 90_000L;
	private static final long WAIT_MS = 5_000;

	private final ObjectMapper mapper = new ObjectMapper();
	private PartyManager manager;
	private LocalPartyAdmissionService admissions;
	private NettySocketServer server;
	private io.micrometer.core.instrument.simple.SimpleMeterRegistry meters;
	private net.osparty.api.service.AccountAuthService auth;
	private final List<WebSocketSession> clients = new ArrayList<>();

	@BeforeEach
	void setUp() {
		NodeIdentity node = new NodeIdentity("node-a", true);
		manager = new PartyManager(mapper, new LocalPartyOwnershipService(node), node,
			new LocalPartyBus(), new LocalNodeLoadRegistry(), sessionId -> { }, MEMBER_TIMEOUT_MS,
			new net.osparty.api.service.PlayerIdService("test-salt"));
		// Port 0: an ephemeral port, so the test never collides with a real run of the service.
		// No ad board in this test: it covers the live-party path, and the board has its own suite.
		// A real registry rather than null: the handler registers a gauge per endpoint on construction, and
		// that is worth exercising rather than skipping.
		meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		admissions = new LocalPartyAdmissionService();
		auth = new net.osparty.api.service.AccountAuthService(
			new net.osparty.api.repository.InMemoryAccountCredentialRepository(),
			new net.osparty.api.service.LocalCouplingCodeStore(), true);
		server = new NettySocketServer(
			new PartyFrameHandler(manager, mapper, admissions), null, 0, meters, false, auth);
		server.start();
	}

	@AfterEach
	void tearDown() {
		for (WebSocketSession client : clients) {
			try {
				client.close();
			}
			catch (Exception ignored) {
			}
		}
		server.stop();
	}

	@Test
	void hostJoinRelayAndLeaveOverRealSockets() throws Exception {
		Collector hostOut = new Collector(Mux.LIVE);
		WebSocketSession host = connect("/api/ws", hostOut);
		host.sendMessage(tagged(Mux.LIVE,
			"{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));
		assertThat(hostOut.await("welcome").get("status").asText()).isEqualTo("HOST");

		Collector memberOut = new Collector(Mux.LIVE);
		WebSocketSession member = connect("/api/ws", memberOut);
		// Named, and granted a seat: auto-admission is keyed on the player, so an anonymous joiner has
		// nothing for the grant to match and would land PENDING however it filled in `invited`.
		admissions.grant("r", "Mem");
		member.sendMessage(tagged(Mux.LIVE,
			"{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222,\"invited\":true}"));
		JsonNode memberWelcome = memberOut.await("welcome");
		assertThat(memberWelcome.get("status").asText()).isEqualTo("MEMBER");
		long memberId = memberWelcome.get("m").asLong();

		// A live update reaching its peer is the whole job of this transport. The frame has to arrive before
		// the flush can carry it, so the flush is driven inside the wait rather than once before it.
		host.sendMessage(tagged(Mux.LIVE, "{\"t\":\"update\",\"s\":{\"currentHp\":42}}"));
		JsonNode relayed = waitFor(() -> {
			manager.flushRooms();
			return memberOut.find("mu");
		});
		assertThat(relayed.get("u").get(0).get("s").get("currentHp").asInt()).isEqualTo(42);

		// Closing the socket releases the member's connection — the close callback is wired, not just the
		// read path — while the room goes on holding its seat.
		member.close(CloseStatus.NORMAL);
		waitFor(() -> manager.connectedMembers() == 1 ? mapper.createObjectNode() : null);
		assertThat(hostOut.find("memberLeft")).isNull();

		// So the same player dialling back in lands in the seat it left, over a real socket and all.
		Collector againOut = new Collector(Mux.LIVE);
		WebSocketSession again = connect("/api/ws", againOut);
		again.sendMessage(tagged(Mux.LIVE, "{\"t\":\"join\",\"room\":\"r\",\"accountHash\":222}"));
		JsonNode back = againOut.await("welcome");
		assertThat(back.get("m").asLong()).isEqualTo(memberId);
		assertThat(back.get("status").asText()).isEqualTo("MEMBER");
	}

	/**
	 * {@code SocketPathHandler.authenticate} stashes {@code AUTH_TOKEN} specifically "to mark it as used
	 * once the connection is established" -- this pins down that the mark actually happens. Real handshake,
	 * real header, over a real socket: nothing about {@code touch} is reachable from a test that talks to
	 * {@code AccountAuthService} directly, since the whole point is that it fires from the transport layer.
	 */
	@Test
	void presentingACredentialOnConnectTouchesIt() throws Exception {
		String token = auth.enrol(4242L, null).orElseThrow().token();
		java.time.Instant issuedAt = auth.devices(4242L).get(0).lastSeenAt();
		Thread.sleep(5); // clock resolution: without a gap, "touched" and "just issued" can land in the same ms

		connectAuthenticated("/api/ws", new Collector(Mux.LIVE), token);

		waitFor(() -> auth.devices(4242L).get(0).lastSeenAt().isAfter(issuedAt)
			? mapper.createObjectNode() : null);
		assertThat(auth.devices(4242L).get(0).lastSeenAt()).isAfter(issuedAt);
	}

	/** The node-hint form is a different path to the same endpoint, and the client is told where it landed. */
	@Test
	void theNodeHintedPathServesTheSameEndpoint() throws Exception {
		Collector out = new Collector(Mux.LIVE);
		WebSocketSession host = connect("/n/node-a/api/ws", out);
		host.sendMessage(tagged(Mux.LIVE,
			"{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));

		JsonNode welcome = out.await("welcome");
		assertThat(welcome.get("status").asText()).isEqualTo("HOST");
		assertThat(welcome.get("nodeId").asText()).isEqualTo("node-a");
	}

	/**
	 * The live party had an endpoint of its own once. It is carried on the merged connection now, and no
	 * released plugin ever dialled the old one — unlike the board's, which is still served for 1.0.50.
	 */
	@Test
	void theRetiredLivePartyPathIsRefused() {
		assertThatThrownBy(() -> connect("/api/v2/ws/party", new Collector(Mux.LIVE)))
			.isInstanceOf(Exception.class);
	}

	/** This port carries WebSockets and nothing else; REST stays on the servlet one. */
	@Test
	void anyOtherPathIsRefused() {
		assertThatThrownBy(() -> connect("/api/v3/nope", new Collector(Mux.LIVE)))
			.isInstanceOf(Exception.class);
	}

	/**
	 * The merged endpoint: the same live party, reached over a connection that could equally be carrying the
	 * ad board. Every frame is prefixed with its channel, in both directions — which is what lets a client in
	 * a party hold one socket instead of two.
	 *
	 * <p>No board is wired up here, so a board-tagged frame has nowhere to go. Sending one anyway is the
	 * point: it must be ignored rather than parsed as a live frame, since the two protocols share frame
	 * names.
	 */
	@Test
	void theMergedEndpointTagsEveryFrameWithItsChannel() throws Exception {
		Collector out = new Collector(Mux.LIVE);
		WebSocketSession host = connect("/api/ws", out);

		// `host` means something in both protocols. Tagged for the board, it must not reach the live party.
		host.sendMessage(tagged(Mux.BOARD,
			"{\"t\":\"host\",\"room\":\"board\",\"hostName\":\"Nobody\",\"capacity\":3}"));
		host.sendMessage(tagged(Mux.LIVE,
			"{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));

		assertThat(out.await("welcome").get("status").asText()).isEqualTo("HOST");
		// One room, from the one frame that was addressed to the live party.
		assertThat(out.find("error")).isNull();
	}

	/**
	 * Open connections are counted, in both directions — up on connect and back down on close. It is the
	 * only number that says how much of the ingress this service is actually costing.
	 */
	@Test
	void openConnectionsAreCounted() throws Exception {
		assertThat(open()).isZero();

		WebSocketSession first = connect("/api/ws", new Collector(Mux.LIVE));
		WebSocketSession second = connect("/n/node-a/api/ws", new Collector(Mux.LIVE));
		waitForCount(2);

		first.close(CloseStatus.NORMAL);
		waitForCount(1);
		second.close(CloseStatus.NORMAL);
		waitForCount(0);
	}

	/**
	 * Merged connections only. The gauge is tagged by the endpoint a connection arrived on — which is what
	 * says whether anyone is still on the board's own path and therefore when it can go — so an untagged
	 * lookup would pick whichever series Micrometer happened to register first.
	 */
	private int open() {
		io.micrometer.core.instrument.Gauge gauge =
			meters.find("osparty.ws.connections").tag("endpoint", "mux").gauge();
		return gauge == null ? -1 : (int) gauge.value();
	}

	/** The close callback runs on the event loop, so the count settles a moment after the socket does. */
	private void waitForCount(int expected) throws Exception {
		waitFor(() -> open() == expected ? mapper.createObjectNode() : null);
	}

	/** A frame for one channel of a merged connection: the tag byte, then the JSON. */
	private static BinaryMessage tagged(byte tag, String json) {
		byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(payload.length + 1);
		buffer.put(tag).put(payload).flip();
		return new BinaryMessage(buffer);
	}

	private WebSocketSession connect(String path, Collector collector) throws Exception {
		WebSocketSession session = new StandardWebSocketClient()
			.execute(collector, "ws://localhost:" + server.boundPort() + path)
			.get(WAIT_MS, TimeUnit.MILLISECONDS);
		clients.add(session);
		return session;
	}

	/** As {@link #connect(String, Collector)}, presenting a credential on the upgrade. */
	private WebSocketSession connectAuthenticated(String path, Collector collector, String token)
		throws Exception {
		org.springframework.web.socket.WebSocketHttpHeaders headers =
			new org.springframework.web.socket.WebSocketHttpHeaders();
		headers.add("X-OSParty-Auth", token);
		WebSocketSession session = new StandardWebSocketClient()
			.execute(collector, headers, java.net.URI.create("ws://localhost:" + server.boundPort() + path))
			.get(WAIT_MS, TimeUnit.MILLISECONDS);
		clients.add(session);
		return session;
	}

	/** Poll until {@code source} produces a frame, because everything here crosses a real socket. */
	private static JsonNode waitFor(FrameSource source) throws Exception {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			JsonNode found = source.get();
			if (found != null) {
				return found;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("frame never arrived within " + WAIT_MS + "ms");
	}

	private interface FrameSource {
		JsonNode get() throws Exception;
	}

	/** Collects one channel's inbound frames so a test can wait for the one it cares about. */
	private final class Collector extends AbstractWebSocketHandler {
		private final List<String> received = new ArrayList<>();
		/** The channel this collector reads; anything tagged for the other one is not its business. */
		private final byte tag;

		Collector(byte tag) {
			this.tag = tag;
		}

		/** The server sends binary (UTF-8 JSON), which is the point of the transport. */
		@Override
		protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
			java.nio.ByteBuffer payload = message.getPayload();
			byte[] bytes = new byte[payload.remaining()];
			payload.get(bytes);
			if (bytes.length == 0 || bytes[0] != tag) {
				return;
			}
			synchronized (received) {
				received.add(new String(bytes, 1, bytes.length - 1,
					java.nio.charset.StandardCharsets.UTF_8));
			}
		}

		/** The last frame of {@code type}, or null. */
		JsonNode find(String type) throws Exception {
			List<String> snapshot;
			synchronized (received) {
				snapshot = new ArrayList<>(received);
			}
			JsonNode found = null;
			for (String frame : snapshot) {
				JsonNode node = mapper.readTree(frame);
				if (type.equals(node.path("t").asText())) {
					found = node;
				}
			}
			return found;
		}

		JsonNode await(String type) throws Exception {
			return waitFor(() -> find(type));
		}
	}
}

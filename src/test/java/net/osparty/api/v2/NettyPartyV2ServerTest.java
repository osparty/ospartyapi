package net.osparty.api.v2;

import net.osparty.api.transport.Mux;
import net.osparty.api.transport.PartySession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.osparty.api.v2.netty.NettyPartyV2Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * The Netty transport over real sockets: a real handshake, real frames, and the close callback that removes
 * a member. The protocol itself is covered by {@link PartyV2HandlerTest} — what is under test here is only
 * that this transport reaches the same frame handler, on both the plain and the node-hinted path, and that
 * it refuses everything else.
 *
 * <p>In this package rather than beside the server because it drives the manager's flush directly, which is
 * internal to the room layer.
 */
class NettyPartyV2ServerTest {
	/** No channel tag: the ad board on its own connection, where every frame is already the board's. */
	private static final byte UNTAGGED = 0;
	private static final long MEMBER_TIMEOUT_MS = 90_000L;
	private static final long WAIT_MS = 5_000;

	private final ObjectMapper mapper = new ObjectMapper();
	private PartyV2Manager manager;
	private NettyPartyV2Server server;
	private io.micrometer.core.instrument.simple.SimpleMeterRegistry meters;
	private final List<WebSocketSession> clients = new ArrayList<>();

	@BeforeEach
	void setUp() {
		NodeIdentity node = new NodeIdentity("node-a", true);
		manager = new PartyV2Manager(mapper, new LocalPartyOwnershipService(node), node,
			new LocalPartyV2Bus(), new LocalNodeLoadRegistry(), MEMBER_TIMEOUT_MS);
		// Port 0: an ephemeral port, so the test never collides with a real run of the service.
		// No ad board in this test: it covers the live-party path, and the board has its own suite.
		// A real registry rather than null: the handler registers a gauge per endpoint on construction, and
		// that is worth exercising rather than skipping.
		meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		server = new NettyPartyV2Server(new PartyV2FrameHandler(manager, mapper), null, 0, meters);
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
		member.sendMessage(tagged(Mux.LIVE, "{\"t\":\"join\",\"room\":\"r\",\"invited\":true}"));
		assertThat(memberOut.await("welcome").get("status").asText()).isEqualTo("MEMBER");

		// A live update reaching its peer is the whole job of this transport. The frame has to arrive before
		// the flush can carry it, so the flush is driven inside the wait rather than once before it.
		host.sendMessage(tagged(Mux.LIVE, "{\"t\":\"update\",\"s\":{\"currentHp\":42}}"));
		JsonNode relayed = waitFor(() -> {
			manager.flushRooms();
			return memberOut.find("mu");
		});
		assertThat(relayed.get("u").get(0).get("s").get("currentHp").asInt()).isEqualTo(42);

		// Closing the socket removes the member: the close callback is wired, not just the read path.
		member.close(CloseStatus.NORMAL);
		assertThat(waitFor(() -> hostOut.find("memberLeft"))).isNotNull();
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

	/** The live party had an endpoint of its own once. It is carried on the merged connection now. */
	@Test
	void theRetiredLivePathIsRefused() {
		assertThatThrownBy(() -> connect("/api/v2/ws/party", new Collector(Mux.LIVE)))
			.isInstanceOf(Exception.class);
	}

	/** This port carries the WebSocket endpoints and nothing else; REST stays on the servlet one. */
	@Test
	void anyOtherPathIsRefused() {
		assertThatThrownBy(() -> connect("/api/v3/nope", new Collector()))
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
	 * Connections are counted by the endpoint they arrived on, which is what decides when the
	 * single-protocol endpoints can be retired. Retiring them on a guess disconnects everyone who has not
	 * updated, so the count has to be right in both directions — up on connect and back down on close.
	 */
	@Test
	void openConnectionsAreCountedPerEndpoint() throws Exception {
		assertThat(openOn("board")).isZero();
		assertThat(openOn("mux")).isZero();

		// No board is wired up in this test, so this connection opens no session — but the endpoint it
		// arrived on is still counted, which is exactly what the gauge is for.
		WebSocketSession board = connect("/api/v1/ws/parties", new Collector());
		WebSocketSession merged = connect("/api/ws", new Collector(Mux.LIVE));
		waitForCount("board", 1);
		waitForCount("mux", 1);

		board.close(CloseStatus.NORMAL);
		waitForCount("board", 0);
		// Closing one endpoint's connection leaves the other's alone.
		assertThat(openOn("mux")).isEqualTo(1);
		merged.close(CloseStatus.NORMAL);
		waitForCount("mux", 0);
	}

	private int openOn(String endpoint) {
		io.micrometer.core.instrument.Gauge gauge = meters.find("osparty.ws.connections")
			.tag("endpoint", endpoint).gauge();
		return gauge == null ? -1 : (int) gauge.value();
	}

	/** The close callback runs on the event loop, so the count settles a moment after the socket does. */
	private void waitForCount(String endpoint, int expected) throws Exception {
		waitFor(() -> openOn(endpoint) == expected ? mapper.createObjectNode() : null);
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

	/** Collects inbound frames so a test can wait for the one it cares about. */
	private final class Collector extends AbstractWebSocketHandler {
		private final List<String> received = new ArrayList<>();
		/** The channel this collector reads, or {@link #UNTAGGED} on a single-protocol socket. */
		private final byte tag;

		Collector() {
			this(UNTAGGED);
		}

		Collector(byte tag) {
			this.tag = tag;
		}

		/** The server sends binary (UTF-8 JSON), which is the point of the transport. */
		@Override
		protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
			java.nio.ByteBuffer payload = message.getPayload();
			byte[] bytes = new byte[payload.remaining()];
			payload.get(bytes);
			int from = 0;
			if (tag != UNTAGGED) {
				// A merged connection: anything not addressed to this channel belongs to the other one.
				if (bytes.length == 0 || bytes[0] != tag) {
					return;
				}
				from = 1;
			}
			synchronized (received) {
				received.add(new String(bytes, from, bytes.length - from,
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

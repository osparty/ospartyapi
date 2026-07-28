package net.osparty.api.v2;

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
	private static final long MEMBER_TIMEOUT_MS = 90_000L;
	private static final long WAIT_MS = 5_000;

	private final ObjectMapper mapper = new ObjectMapper();
	private PartyV2Manager manager;
	private NettyPartyV2Server server;
	private final List<WebSocketSession> clients = new ArrayList<>();

	@BeforeEach
	void setUp() {
		NodeIdentity node = new NodeIdentity("node-a", true);
		manager = new PartyV2Manager(mapper, new LocalPartyOwnershipService(node), node,
			new LocalPartyV2Bus(), new LocalNodeLoadRegistry(), MEMBER_TIMEOUT_MS);
		// Port 0: an ephemeral port, so the test never collides with a real run of the service.
		// No ad board in this test: it covers the live-party path, and the board has its own suite.
		server = new NettyPartyV2Server(new PartyV2FrameHandler(manager, mapper), null, 0);
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
		Collector hostOut = new Collector();
		WebSocketSession host = connect("/api/v2/ws/party", hostOut);
		host.sendMessage(new TextMessage(
			"{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));
		assertThat(hostOut.await("welcome").get("status").asText()).isEqualTo("HOST");

		Collector memberOut = new Collector();
		WebSocketSession member = connect("/api/v2/ws/party", memberOut);
		member.sendMessage(new TextMessage("{\"type\":\"join\",\"room\":\"r\",\"invited\":true}"));
		assertThat(memberOut.await("welcome").get("status").asText()).isEqualTo("MEMBER");

		// A live update reaching its peer is the whole job of this transport. The frame has to arrive before
		// the flush can carry it, so the flush is driven inside the wait rather than once before it.
		host.sendMessage(new TextMessage("{\"type\":\"update\",\"state\":{\"currentHp\":42}}"));
		JsonNode relayed = waitFor(() -> {
			manager.flushRooms();
			return memberOut.find("memberUpdates");
		});
		assertThat(relayed.get("updates").get(0).get("state").get("currentHp").asInt()).isEqualTo(42);

		// Closing the socket removes the member: the close callback is wired, not just the read path.
		member.close(CloseStatus.NORMAL);
		assertThat(waitFor(() -> hostOut.find("memberLeft"))).isNotNull();
	}

	/** The node-hint form is a different path to the same endpoint, and the client is told where it landed. */
	@Test
	void theNodeHintedPathServesTheSameEndpoint() throws Exception {
		Collector out = new Collector();
		WebSocketSession host = connect("/n/node-a/api/v2/ws/party", out);
		host.sendMessage(new TextMessage(
			"{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));

		JsonNode welcome = out.await("welcome");
		assertThat(welcome.get("status").asText()).isEqualTo("HOST");
		assertThat(welcome.get("nodeId").asText()).isEqualTo("node-a");
	}

	/** This port carries the two WebSocket endpoints and nothing else; REST stays on the servlet one. */
	@Test
	void anyOtherPathIsRefused() {
		assertThatThrownBy(() -> connect("/api/v3/nope", new Collector()))
			.isInstanceOf(Exception.class);
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

		/** The server sends binary (UTF-8 JSON), which is the point of the transport. */
		@Override
		protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
			java.nio.ByteBuffer payload = message.getPayload();
			byte[] bytes = new byte[payload.remaining()];
			payload.get(bytes);
			synchronized (received) {
				received.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
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
				if (type.equals(node.path("type").asText())) {
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

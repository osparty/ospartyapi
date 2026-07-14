package net.osparty.api.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.ws.reconcile-interval-ms=150")
class PartyInviteSocketTest {
	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void invitePushesToOnlineFriendAndAcksHost() throws Exception {
		BlockingQueue<JsonNode> hostMsgs = new LinkedBlockingQueue<>();
		BlockingQueue<JsonNode> friendMsgs = new LinkedBlockingQueue<>();
		WebSocketSession host = connect(hostMsgs);
		WebSocketSession friend = connect(friendMsgs);
		try {
			host.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-inv\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsInviteHost\",\"capacity\":3,\"passphrase\":\"pp-inv\"}}"));
			JsonNode hosted = awaitWhere(hostMsgs, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("party").path("id").asText();

			// The friend registers its identity so the server can route an invite to it.
			friend.sendMessage(new TextMessage("{\"type\":\"identify\",\"accountHash\":222,\"name\":\"Friendo\"}"));
			// The host identifies too (host name matches the party, authorising the invite).
			host.sendMessage(new TextMessage("{\"type\":\"identify\",\"accountHash\":111,\"name\":\"WsInviteHost\"}"));

			host.sendMessage(new TextMessage(
				"{\"type\":\"invite\",\"id\":\"" + id + "\",\"name\":\"WsInviteHost\",\"target\":\"Friendo\"}"));

			JsonNode invited = awaitWhere(friendMsgs, m -> "invited".equals(type(m)), "invited push");
			assertThat(invited.path("party").path("id").asText()).isEqualTo(id);
			assertThat(invited.path("from").asText()).isEqualTo("WsInviteHost");

			JsonNode ack = awaitWhere(hostMsgs, m -> "inviteAck".equals(type(m)), "inviteAck");
			assertThat(ack.path("id").asText()).isEqualTo("Friendo");
			assertThat(ack.path("delivered").asBoolean()).isTrue();
		}
		finally {
			host.close();
			friend.close();
		}
	}

	@Test
	void inviteToOfflineFriendAcksNotDelivered() throws Exception {
		BlockingQueue<JsonNode> hostMsgs = new LinkedBlockingQueue<>();
		WebSocketSession host = connect(hostMsgs);
		try {
			host.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-inv2\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsInviteHost2\",\"capacity\":3,\"passphrase\":\"pp-inv2\"}}"));
			JsonNode hosted = awaitWhere(hostMsgs, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("party").path("id").asText();

			host.sendMessage(new TextMessage(
				"{\"type\":\"invite\",\"id\":\"" + id + "\",\"name\":\"WsInviteHost2\",\"target\":\"NobodyHere\"}"));

			JsonNode ack = awaitWhere(hostMsgs, m -> "inviteAck".equals(type(m)), "inviteAck");
			assertThat(ack.path("id").asText()).isEqualTo("NobodyHere");
			assertThat(ack.path("delivered").asBoolean()).isFalse();
		}
		finally {
			host.close();
		}
	}

	@Test
	void inviteFromNonMemberIsRejected() throws Exception {
		BlockingQueue<JsonNode> hostMsgs = new LinkedBlockingQueue<>();
		BlockingQueue<JsonNode> strangerMsgs = new LinkedBlockingQueue<>();
		WebSocketSession host = connect(hostMsgs);
		WebSocketSession stranger = connect(strangerMsgs);
		try {
			host.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-inv3\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsInviteHost3\",\"capacity\":3,\"passphrase\":\"pp-inv3\"}}"));
			JsonNode hosted = awaitWhere(hostMsgs, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("party").path("id").asText();

			// A stranger who is neither host nor member may not invite into the party.
			stranger.sendMessage(new TextMessage(
				"{\"type\":\"invite\",\"id\":\"" + id + "\",\"name\":\"RandomGuy\",\"accountHash\":999,"
					+ "\"target\":\"Friendo\"}"));

			JsonNode err = awaitWhere(strangerMsgs, m -> "error".equals(type(m)), "error");
			assertThat(err.path("detail").asText()).isEqualTo("not in party");
		}
		finally {
			host.close();
			stranger.close();
		}
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

	private static String type(JsonNode msg) {
		return msg.path("type").asText();
	}

	private JsonNode awaitWhere(BlockingQueue<JsonNode> messages, Predicate<JsonNode> match, String desc)
		throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			JsonNode msg = messages.poll(5, TimeUnit.SECONDS);
			if (msg != null && match.test(msg)) {
				return msg;
			}
		}
		throw new AssertionError("Timed out waiting for " + desc);
	}
}

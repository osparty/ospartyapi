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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"app.rate-limit.interval-ms=0",
	"app.ws.reconcile-interval-ms=150"
})
class PartyWebSocketTest {
	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void snapshotOnSubscribeThenCreatedDelta() throws Exception {
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(new TextMessage("{\"type\":\"subscribe\"}"));

			JsonNode snapshot = awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");
			assertThat(snapshot.has("parties")).isTrue();

			String body = "{\"activity\":\"cox\",\"host\":\"WsTester\",\"description\":\"trio\","
				+ "\"capacity\":3,\"world\":\"301\",\"passphrase\":\"wine-of-zamorak\"}";
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			rest.postForEntity("/api/v1/parties", new HttpEntity<>(body, headers), String.class);

			JsonNode created = awaitWhere(messages,
				m -> "created".equals(type(m)) && "WsTester".equals(m.path("party").path("host").asText()),
				"created for WsTester");
			assertThat(created.get("party").get("activity").asText()).isEqualTo("cox");
		}
		finally {
			session.close();
		}
	}

	@Test
	void hostOverSocketAcksAndBroadcasts() throws Exception {
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(new TextMessage("{\"type\":\"subscribe\"}"));
			awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");

			session.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-host\",\"request\":"
				+ "{\"activity\":\"tob\",\"host\":\"WsHost\",\"capacity\":3,\"passphrase\":\"pp-host\"}}"));

			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			assertThat(hosted.path("party").path("host").asText()).isEqualTo("WsHost");
			assertThat(hosted.path("party").path("id").asText()).isNotBlank();
			assertThat(hosted.path("party").path("inviteCode").asText()).isNotBlank();

			awaitWhere(messages,
				m -> "created".equals(type(m)) && "WsHost".equals(m.path("party").path("host").asText()),
				"created for WsHost");
		}
		finally {
			session.close();
		}
	}

	@Test
	void updateOverSocketChangesField() throws Exception {
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(new TextMessage("{\"type\":\"subscribe\"}"));
			awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");

			session.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-upd\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsUpdater\",\"capacity\":3,\"passphrase\":\"pp-upd\","
				+ "\"description\":\"original\"}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("party").path("id").asText();

			awaitWhere(messages, m -> "created".equals(type(m)) && id.equals(m.path("party").path("id").asText()),
				"created for the hosted ad");

			session.sendMessage(new TextMessage(
				"{\"type\":\"update\",\"id\":\"" + id + "\",\"patch\":{\"description\":\"changed!\"}}"));

			JsonNode updated = awaitWhere(messages,
				m -> "updated".equals(type(m)) && id.equals(m.path("party").path("id").asText())
					&& "changed!".equals(m.path("party").path("description").asText()),
				"updated with new description");
			assertThat(updated.path("party").path("description").asText()).isEqualTo("changed!");
		}
		finally {
			session.close();
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

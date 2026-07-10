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
class PartyWebSocketTest {
	@LocalServerPort
	private int port;

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

			session.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-snap\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsTester\",\"description\":\"trio\","
				+ "\"capacity\":3,\"world\":\"301\",\"passphrase\":\"wine-of-zamorak\"}}"));

			JsonNode batch = awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("created"), p -> "WsTester".equals(p.path("host").asText())),
				"created for WsTester");
			JsonNode party = firstMatch(batch.path("created"), p -> "WsTester".equals(p.path("host").asText()));
			assertThat(party.get("activity").asText()).isEqualTo("cox");
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
				m -> "batch".equals(type(m)) && anyMatch(m.path("created"), p -> "WsHost".equals(p.path("host").asText())),
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

			awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("created"), p -> id.equals(p.path("id").asText())),
				"created for the hosted ad");

			session.sendMessage(new TextMessage(
				"{\"type\":\"update\",\"id\":\"" + id + "\",\"patch\":{\"description\":\"changed!\"}}"));

			JsonNode batch = awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("updated"),
					d -> id.equals(d.path("id").asText()) && "changed!".equals(d.path("description").asText())),
				"updated delta with new description");
			JsonNode delta = firstMatch(batch.path("updated"), d -> id.equals(d.path("id").asText()));
			assertThat(delta.path("description").asText()).isEqualTo("changed!");
		}
		finally {
			session.close();
		}
	}

	@Test
	void updateOverSocketChangesRolesAndLearner() throws Exception {
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(new TextMessage("{\"type\":\"subscribe\"}"));
			awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");

			session.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-roles\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsRoles\",\"capacity\":3,\"passphrase\":\"pp-roles\","
				+ "\"requiredRoles\":[\"melee\",\"fill\",\"fill\"],\"hostRole\":\"melee\",\"learner\":false}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("party").path("id").asText();

			awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("created"), p -> id.equals(p.path("id").asText())),
				"created for the hosted ad");

			session.sendMessage(new TextMessage("{\"type\":\"update\",\"id\":\"" + id + "\",\"patch\":"
				+ "{\"requiredRoles\":[\"mage\",\"range\",\"fill\"],\"hostRole\":\"mage\",\"learner\":true}}"));

			JsonNode batch = awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("updated"),
					d -> id.equals(d.path("id").asText()) && d.path("learner").asBoolean()),
				"updated delta with new roles + learner");
			JsonNode delta = firstMatch(batch.path("updated"), d -> id.equals(d.path("id").asText()));
			assertThat(delta.path("hostRole").asText()).isEqualTo("mage");
			assertThat(delta.path("requiredRoles").get(0).asText()).isEqualTo("mage");
			assertThat(delta.path("learner").asBoolean()).isTrue();
			// neededRoles re-seeds from the new composition minus the host's role.
			assertThat(delta.path("neededRoles").toString()).isEqualTo("[\"range\",\"fill\"]");
		}
		finally {
			session.close();
		}
	}

	@Test
	void transferHostReassignsAdAndReKeys() throws Exception {
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(new TextMessage("{\"type\":\"subscribe\"}"));
			awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");

			session.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-old\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsXfer\",\"capacity\":3,\"passphrase\":\"pp-xfer\"}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("party").path("id").asText();
			awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("created"), p -> id.equals(p.path("id").asText())),
				"created for the hosted ad");

			// Hand the ad to WsXfer2, re-keying the credential to k-new.
			session.sendMessage(new TextMessage("{\"type\":\"transferHost\",\"id\":\"" + id
				+ "\",\"key\":\"k-old\",\"host\":\"WsXfer2\",\"newKey\":\"k-new\"}"));
			JsonNode ack = awaitWhere(messages,
				m -> "transferred".equals(type(m)) && id.equals(m.path("id").asText()), "transferred ack");
			assertThat(ack.path("id").asText()).isEqualTo(id);

			// The host-name change ships to search clients as a normal reconcile delta.
			awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("updated"),
					d -> id.equals(d.path("id").asText()) && "WsXfer2".equals(d.path("host").asText())),
				"updated delta with new host");

			// The old key no longer authorises (session was unbound + credential re-keyed).
			session.sendMessage(new TextMessage("{\"type\":\"update\",\"id\":\"" + id
				+ "\",\"key\":\"k-old\",\"patch\":{\"description\":\"stale\"}}"));
			awaitWhere(messages,
				m -> "error".equals(type(m)) && id.equals(m.path("id").asText())
					&& "forbidden".equals(m.path("detail").asText()),
				"forbidden for the old key");

			// The new key authorises the new host's writes.
			session.sendMessage(new TextMessage("{\"type\":\"update\",\"id\":\"" + id
				+ "\",\"key\":\"k-new\",\"patch\":{\"description\":\"new-host-desc\"}}"));
			awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("updated"),
					d -> id.equals(d.path("id").asText()) && "new-host-desc".equals(d.path("description").asText())),
				"updated delta from the new host");
		}
		finally {
			session.close();
		}
	}

	@Test
	void getByCodeReturnsPrivatePartyAndMissCarriesNoParty() throws Exception {
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-code\",\"request\":"
				+ "{\"activity\":\"toa\",\"host\":\"WsPriv\",\"capacity\":2,\"passphrase\":\"pp\","
				+ "\"privateParty\":true}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String code = hosted.path("party").path("inviteCode").asText();
			assertThat(code).isNotBlank();

			session.sendMessage(new TextMessage("{\"type\":\"getByCode\",\"code\":\"" + code + "\"}"));
			JsonNode found = awaitWhere(messages, m -> "byCode".equals(type(m)), "byCode hit");
			assertThat(found.path("party").path("host").asText()).isEqualTo("WsPriv");

			session.sendMessage(new TextMessage("{\"type\":\"getByCode\",\"code\":\"ZZZZZZ\"}"));
			JsonNode miss = awaitWhere(messages,
				m -> "byCode".equals(type(m)) && "ZZZZZZ".equals(m.path("id").asText()), "byCode miss");
			assertThat(miss.has("party")).isFalse();
		}
		finally {
			session.close();
		}
	}

	@Test
	void getByHostReturnsHostedAdAndMissCarriesNoParty() throws Exception {
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(new TextMessage("{\"type\":\"host\",\"key\":\"k-host\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsByHost\",\"capacity\":3,\"passphrase\":\"pp\"}}"));
			awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");

			session.sendMessage(new TextMessage("{\"type\":\"getByHost\",\"host\":\"WsByHost\"}"));
			JsonNode found = awaitWhere(messages, m -> "byHost".equals(type(m)), "byHost hit");
			assertThat(found.path("party").path("host").asText()).isEqualTo("WsByHost");

			session.sendMessage(new TextMessage("{\"type\":\"getByHost\",\"host\":\"NobodyHere\"}"));
			JsonNode miss = awaitWhere(messages,
				m -> "byHost".equals(type(m)) && "NobodyHere".equals(m.path("id").asText()), "byHost miss");
			assertThat(miss.has("party")).isFalse();
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

	private static boolean anyMatch(JsonNode array, Predicate<JsonNode> match) {
		return firstMatch(array, match) != null;
	}

	private static JsonNode firstMatch(JsonNode array, Predicate<JsonNode> match) {
		if (array != null && array.isArray()) {
			for (JsonNode node : array) {
				if (match.test(node)) {
					return node;
				}
			}
		}
		return null;
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

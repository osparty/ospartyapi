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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"app.ws.reconcile-interval-ms=150",
	// Every WebSocket is served by Netty on its own port, not by the servlet container's. Zero takes an
	// ephemeral one so the whole suite can run without colliding on 8081.
	"app.socket.port=0"})
class BoardSocketTest {
	@Autowired
	private net.osparty.api.party.netty.NettySocketServer socketServer;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void snapshotOnSubscribeThenCreatedDelta() throws Exception {
		BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
		WebSocketSession session = connect(messages);
		try {
			session.sendMessage(BoardChannel.frame("{\"type\":\"subscribe\"}"));

			JsonNode snapshot = awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");
			assertThat(snapshot.has("ads")).isTrue();

			session.sendMessage(BoardChannel.frame("{\"type\":\"host\",\"key\":\"k-snap\",\"request\":"
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
			session.sendMessage(BoardChannel.frame("{\"type\":\"subscribe\"}"));
			awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");

			session.sendMessage(BoardChannel.frame("{\"type\":\"host\",\"key\":\"k-host\",\"request\":"
				+ "{\"activity\":\"tob\",\"host\":\"WsHost\",\"capacity\":3,\"passphrase\":\"pp-host\"}}"));

			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			assertThat(hosted.path("ad").path("host").asText()).isEqualTo("WsHost");
			assertThat(hosted.path("ad").path("id").asText()).isNotBlank();
			assertThat(hosted.path("ad").path("inviteCode").asText()).isNotBlank();

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
			session.sendMessage(BoardChannel.frame("{\"type\":\"subscribe\"}"));
			awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");

			session.sendMessage(BoardChannel.frame("{\"type\":\"host\",\"key\":\"k-upd\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsUpdater\",\"capacity\":3,\"passphrase\":\"pp-upd\","
				+ "\"description\":\"original\"}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("ad").path("id").asText();

			awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("created"), p -> id.equals(p.path("id").asText())),
				"created for the hosted ad");

			session.sendMessage(BoardChannel.frame(
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
			session.sendMessage(BoardChannel.frame("{\"type\":\"subscribe\"}"));
			awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");

			session.sendMessage(BoardChannel.frame("{\"type\":\"host\",\"key\":\"k-roles\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsRoles\",\"capacity\":3,\"passphrase\":\"pp-roles\","
				+ "\"requiredRoles\":[\"melee\",\"fill\",\"fill\"],\"hostRole\":\"melee\",\"learner\":false}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("ad").path("id").asText();

			awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("created"), p -> id.equals(p.path("id").asText())),
				"created for the hosted ad");

			session.sendMessage(BoardChannel.frame("{\"type\":\"update\",\"id\":\"" + id + "\",\"patch\":"
				+ "{\"requiredRoles\":[\"mage\",\"range\",\"fill\"],\"hostRole\":\"mage\",\"learner\":true}}"));

			JsonNode batch = awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("updated"),
					d -> id.equals(d.path("id").asText()) && d.path("learner").asBoolean()),
				"updated delta with new roles + learner");
			JsonNode delta = firstMatch(batch.path("updated"), d -> id.equals(d.path("id").asText()));
			assertThat(delta.path("hostRole").asText()).isEqualTo("mage");
			assertThat(delta.path("requiredRoles").get(0).asText()).isEqualTo("mage");
			assertThat(delta.path("learner").asBoolean()).isTrue();
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
			session.sendMessage(BoardChannel.frame("{\"type\":\"subscribe\"}"));
			awaitWhere(messages, m -> "snapshot".equals(type(m)), "snapshot");

			session.sendMessage(BoardChannel.frame("{\"type\":\"host\",\"key\":\"k-old\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsXfer\",\"capacity\":3,\"passphrase\":\"pp-xfer\"}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String id = hosted.path("ad").path("id").asText();
			awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("created"), p -> id.equals(p.path("id").asText())),
				"created for the hosted ad");

			session.sendMessage(BoardChannel.frame("{\"type\":\"transferHost\",\"id\":\"" + id
				+ "\",\"key\":\"k-old\",\"host\":\"WsXfer2\",\"newKey\":\"k-new\"}"));
			JsonNode ack = awaitWhere(messages,
				m -> "transferred".equals(type(m)) && id.equals(m.path("id").asText()), "transferred ack");
			assertThat(ack.path("id").asText()).isEqualTo(id);

			awaitWhere(messages,
				m -> "batch".equals(type(m)) && anyMatch(m.path("updated"),
					d -> id.equals(d.path("id").asText()) && "WsXfer2".equals(d.path("host").asText())),
				"updated delta with new host");

			session.sendMessage(BoardChannel.frame("{\"type\":\"update\",\"id\":\"" + id
				+ "\",\"key\":\"k-old\",\"patch\":{\"description\":\"stale\"}}"));
			awaitWhere(messages,
				m -> "error".equals(type(m)) && id.equals(m.path("id").asText())
					&& "forbidden".equals(m.path("detail").asText()),
				"forbidden for the old key");

			session.sendMessage(BoardChannel.frame("{\"type\":\"update\",\"id\":\"" + id
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
			session.sendMessage(BoardChannel.frame("{\"type\":\"host\",\"key\":\"k-code\",\"request\":"
				+ "{\"activity\":\"toa\",\"host\":\"WsPriv\",\"capacity\":2,\"passphrase\":\"pp\","
				+ "\"privateParty\":true}}"));
			JsonNode hosted = awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");
			String code = hosted.path("ad").path("inviteCode").asText();
			assertThat(code).isNotBlank();

			session.sendMessage(BoardChannel.frame("{\"type\":\"getByCode\",\"code\":\"" + code + "\"}"));
			JsonNode found = awaitWhere(messages, m -> "byCode".equals(type(m)), "byCode hit");
			assertThat(found.path("ad").path("host").asText()).isEqualTo("WsPriv");

			session.sendMessage(BoardChannel.frame("{\"type\":\"getByCode\",\"code\":\"ZZZZZZ\"}"));
			JsonNode miss = awaitWhere(messages,
				m -> "byCode".equals(type(m)) && "ZZZZZZ".equals(m.path("id").asText()), "byCode miss");
			assertThat(miss.has("ad")).isFalse();
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
			session.sendMessage(BoardChannel.frame("{\"type\":\"host\",\"key\":\"k-host\",\"request\":"
				+ "{\"activity\":\"cox\",\"host\":\"WsByHost\",\"capacity\":3,\"passphrase\":\"pp\"}}"));
			awaitWhere(messages, m -> "hosted".equals(type(m)), "hosted ack");

			session.sendMessage(BoardChannel.frame("{\"type\":\"getByHost\",\"host\":\"WsByHost\"}"));
			JsonNode found = awaitWhere(messages, m -> "byHost".equals(type(m)), "byHost hit");
			assertThat(found.path("ad").path("host").asText()).isEqualTo("WsByHost");

			session.sendMessage(BoardChannel.frame("{\"type\":\"getByHost\",\"host\":\"NobodyHere\"}"));
			JsonNode miss = awaitWhere(messages,
				m -> "byHost".equals(type(m)) && "NobodyHere".equals(m.path("id").asText()), "byHost miss");
			assertThat(miss.has("ad")).isFalse();
		}
		finally {
			session.close();
		}
	}

	private WebSocketSession connect(BlockingQueue<JsonNode> messages) throws Exception {
		return BoardChannel.connect(socketServer.boundPort(), mapper, messages);
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

package net.osparty.api.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Drives {@link PartyV2Handler} end-to-end with mock sessions to cover the P1 server core: host, join
 * (as a PENDING applicant), live-state relay, server-authoritative admission, and kick.
 */
class PartyV2HandlerTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private PartyV2Handler handler;

	private WebSocketSession host;
	private WebSocketSession member;
	private List<String> hostOut;
	private List<String> memberOut;

	@BeforeEach
	void setUp() {
		NodeIdentity node = new NodeIdentity("node-a", true);
		PartyV2Manager manager = new PartyV2Manager(mapper, new LocalPartyOwnershipService(node), node);
		handler = new PartyV2Handler(manager, mapper);
		hostOut = new ArrayList<>();
		memberOut = new ArrayList<>();
		host = session("host", hostOut);
		member = session("member", memberOut);
		handler.afterConnectionEstablished(host);
		handler.afterConnectionEstablished(member);
	}

	@Test
	void hostJoinRelayAdmitKick() throws Exception {
		send(host, "{\"type\":\"hello\",\"accountHash\":111,\"name\":\"Host\"}");
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":2}");

		JsonNode welcome = last(hostOut, "welcome");
		assertThat(welcome).isNotNull();
		assertThat(welcome.get("status").asText()).isEqualTo("HOST");
		long hostId = welcome.get("memberId").asLong();
		assertThat(last(hostOut, "roster").get("members")).hasSize(1);

		// A joiner lands as PENDING; the host's roster now shows the applicant.
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");
		JsonNode memberWelcome = last(memberOut, "welcome");
		assertThat(memberWelcome.get("status").asText()).isEqualTo("PENDING");
		long memberId = memberWelcome.get("memberId").asLong();
		assertThat(memberId).isNotEqualTo(hostId);

		JsonNode hostRoster = last(hostOut, "roster");
		assertThat(hostRoster.get("members")).hasSize(2);
		assertThat(statusOf(hostRoster, memberId)).isEqualTo("PENDING");

		// A live snapshot from the host is relayed to the member (not echoed to the sender).
		int before = countOf(hostOut, "memberState");
		send(host, "{\"type\":\"state\",\"state\":{\"name\":\"Host\",\"world\":301,\"currentHp\":50}}");
		JsonNode memberState = last(memberOut, "memberState");
		assertThat(memberState).isNotNull();
		assertThat(memberState.get("memberId").asLong()).isEqualTo(hostId);
		assertThat(memberState.get("state").get("world").asInt()).isEqualTo(301);
		assertThat(countOf(hostOut, "memberState")).isEqualTo(before);

		// Host admits the applicant -> server-authoritative status flips to MEMBER for everyone.
		send(host, "{\"type\":\"command\",\"action\":\"ADMIT\",\"target\":" + memberId + "}");
		assertThat(statusOf(last(hostOut, "roster"), memberId)).isEqualTo("MEMBER");
		assertThat(statusOf(last(memberOut, "roster"), memberId)).isEqualTo("MEMBER");

		// Host kicks the member -> the target is told, and the roster drops back to the host alone.
		send(host, "{\"type\":\"command\",\"action\":\"KICK\",\"target\":" + memberId + "}");
		assertThat(last(memberOut, "kicked")).isNotNull();
		assertThat(last(hostOut, "roster").get("members")).hasSize(1);
	}

	@Test
	void joiningUnknownRoomErrors() throws Exception {
		send(member, "{\"type\":\"join\",\"room\":\"nope\"}");
		assertThat(last(memberOut, "error").get("detail").asText()).isEqualTo("no room");
	}

	@Test
	void joiningRoomOwnedElsewhereRedirects() throws Exception {
		// A room this node does not host but another node owns -> the joiner is sent to that owner.
		NodeIdentity node = new NodeIdentity("node-a", true);
		PartyOwnershipService foreign = new PartyOwnershipService() {
			public Claim claim(String room) {
				return Claim.OWNED_BY_OTHER;
			}

			public java.util.Optional<Owner> lookup(String room) {
				return java.util.Optional.of(new Owner("node-b"));
			}

			public void renew(String room) {
			}

			public void release(String room) {
			}
		};
		PartyV2Handler redirecting = new PartyV2Handler(new PartyV2Manager(mapper, foreign, node), mapper);
		List<String> out = new ArrayList<>();
		WebSocketSession joiner = session("joiner", out);
		redirecting.afterConnectionEstablished(joiner);

		redirecting.handleTextMessage(joiner, new TextMessage("{\"type\":\"join\",\"room\":\"elsewhere\"}"));

		assertThat(last(out, "redirect").get("nodeId").asText()).isEqualTo("node-b");
	}

	@Test
	void hostLeavingClosesTheRoomForMembers() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		handler.afterConnectionClosed(host, org.springframework.web.socket.CloseStatus.NORMAL);

		JsonNode roster = last(memberOut, "roster");
		assertThat(roster.get("closed").asBoolean()).isTrue();
	}

	// ---- helpers ------------------------------------------------------------

	private void send(WebSocketSession session, String json) throws Exception {
		handler.handleTextMessage(session, new TextMessage(json));
	}

	private JsonNode last(List<String> out, String type) throws Exception {
		JsonNode found = null;
		for (String json : out) {
			JsonNode node = mapper.readTree(json);
			if (type.equals(node.path("type").asText())) {
				found = node;
			}
		}
		return found;
	}

	private int countOf(List<String> out, String type) throws Exception {
		int n = 0;
		for (String json : out) {
			if (type.equals(mapper.readTree(json).path("type").asText())) {
				n++;
			}
		}
		return n;
	}

	private static String statusOf(JsonNode roster, long memberId) {
		for (JsonNode m : roster.get("members")) {
			if (m.get("memberId").asLong() == memberId) {
				return m.get("status").asText();
			}
		}
		return null;
	}

	private static WebSocketSession session(String id, List<String> out) {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getId()).thenReturn(id);
		when(session.isOpen()).thenReturn(true);
		try {
			doAnswer(inv -> {
				out.add(((TextMessage) inv.getArgument(0)).getPayload());
				return null;
			}).when(session).sendMessage(any());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		return session;
	}
}

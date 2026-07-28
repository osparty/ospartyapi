package net.osparty.api.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Covers what {@link PartyV2Heartbeat} actually calls, rather than what {@link PartyV2Manager} can do.
 *
 * <p>This exists because the ghost sweep shipped unwired: {@code pruneRoom} was implemented and unit tested
 * against the manager directly, but never invoked from the heartbeat. It compiled, every test passed, and
 * the {@code pruned} counter read zero in production for days — which looks identical to "there was nothing
 * to sweep". A test that drives the scheduled method is the only kind that would have caught it.
 */
class PartyV2HeartbeatTest {
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void theScheduledRenewalAlsoSweepsGhosts() throws Exception {
		NodeIdentity node = new NodeIdentity("node-a", true);
		// Zero-length silence window: every seated member counts as gone the moment the sweep looks.
		PartyV2Manager manager = new PartyV2Manager(
			mapper, new LocalPartyOwnershipService(node), node, new LocalPartyV2Bus(),
			new LocalNodeLoadRegistry(), -1L);
		PartyV2Handler handler = new PartyV2Handler(new PartyV2FrameHandler(manager, mapper));

		List<String> out = new ArrayList<>();
		WebSocketSession host = session("host", out);
		handler.afterConnectionEstablished(host);
		handler.handleTextMessage(host, new TextMessage(
			"{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));
		assertThat(manager.roomCount()).isEqualTo(1);

		new PartyV2Heartbeat(manager).renewOwned();

		assertThat(manager.prunedCount()).isEqualTo(1);
		assertThat(manager.roomCount()).isZero();
	}

	@Test
	void theScheduledRenewalLeavesALiveRoomAlone() throws Exception {
		NodeIdentity node = new NodeIdentity("node-a", true);
		PartyV2Manager manager = new PartyV2Manager(
			mapper, new LocalPartyOwnershipService(node), node, new LocalPartyV2Bus(),
			new LocalNodeLoadRegistry(), 90_000L);
		PartyV2Handler handler = new PartyV2Handler(new PartyV2FrameHandler(manager, mapper));

		List<String> out = new ArrayList<>();
		WebSocketSession host = session("host", out);
		handler.afterConnectionEstablished(host);
		handler.handleTextMessage(host, new TextMessage(
			"{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));

		new PartyV2Heartbeat(manager).renewOwned();

		assertThat(manager.prunedCount()).isZero();
		assertThat(manager.roomCount()).isEqualTo(1);
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

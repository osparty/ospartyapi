package net.osparty.api.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.osparty.api.transport.PartySession;
import org.junit.jupiter.api.Test;

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
	void theScheduledRenewalAlsoSweepsGhosts() {
		NodeIdentity node = new NodeIdentity("node-a", true);
		// Zero-length silence window: every seated member counts as gone the moment the sweep looks.
		PartyV2Manager manager = new PartyV2Manager(
			mapper, new LocalPartyOwnershipService(node), node, new LocalPartyV2Bus(),
			new LocalNodeLoadRegistry(), -1L);
		PartyV2FrameHandler handler = new PartyV2FrameHandler(manager, mapper);

		hostARoom(handler);
		assertThat(manager.roomCount()).isEqualTo(1);

		new PartyV2Heartbeat(manager).renewOwned();

		assertThat(manager.prunedCount()).isEqualTo(1);
		assertThat(manager.roomCount()).isZero();
	}

	@Test
	void theScheduledRenewalLeavesALiveRoomAlone() {
		NodeIdentity node = new NodeIdentity("node-a", true);
		PartyV2Manager manager = new PartyV2Manager(
			mapper, new LocalPartyOwnershipService(node), node, new LocalPartyV2Bus(),
			new LocalNodeLoadRegistry(), 90_000L);
		PartyV2FrameHandler handler = new PartyV2FrameHandler(manager, mapper);

		hostARoom(handler);

		new PartyV2Heartbeat(manager).renewOwned();

		assertThat(manager.prunedCount()).isZero();
		assertThat(manager.roomCount()).isEqualTo(1);
	}

	private static void hostARoom(PartyV2FrameHandler handler) {
		PartySession host = new CollectingSession("host");
		handler.onOpen(host);
		handler.onMessage(host.id(),
			"{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"
				.getBytes(StandardCharsets.UTF_8));
	}

	/** A connection with no transport under it. What it received does not matter here, only that it is open. */
	private static final class CollectingSession implements PartySession {
		private final String id;
		private final List<String> out = new ArrayList<>();

		CollectingSession(String id) {
			this.id = id;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public boolean isOpen() {
			return true;
		}

		@Override
		public void send(byte[] frame) {
			out.add(new String(frame, StandardCharsets.UTF_8));
		}

		@Override
		public void sendText(String json) {
			out.add(json);
		}

		@Override
		public void close() {
		}

		@Override
		public boolean nodeHinted() {
			return false;
		}
	}
}

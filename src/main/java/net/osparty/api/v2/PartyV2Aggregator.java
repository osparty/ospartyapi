package net.osparty.api.v2;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flushes each owned room's collected live updates on a fixed window.
 *
 * <p>Relaying every update the moment it lands owes one send per peer, so a room's outbound frames grow with
 * the square of its size — a five-man costs twenty sends a tick, an eight-man fifty-six. Collecting a window
 * and giving each member one frame with everything in it makes that linear, and sends are the expensive part:
 * each one takes a session's lock, buffers and writes.
 *
 * <p>The window is deliberately well under a game tick, so it adds a fraction of a tick of latency and a
 * member rarely queues twice inside one. Pings, ready checks and spec drains are not collected here — they
 * are one-off and want to be prompt.
 *
 * <p>This is a periodic sweep of all owned rooms, which §11.1 of the migration plan warns against on the hot
 * path. It is cheap by construction: a room with nothing pending returns on an empty check, and the flush
 * itself takes only that room's own lock, so rooms stay independent.
 */
@Component
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2Aggregator {
	private final PartyV2Manager manager;

	public PartyV2Aggregator(PartyV2Manager manager) {
		this.manager = manager;
	}

	@Scheduled(fixedRateString = "${app.party-v2.aggregate-ms:100}")
	void flush() {
		manager.flushRooms();
	}
}

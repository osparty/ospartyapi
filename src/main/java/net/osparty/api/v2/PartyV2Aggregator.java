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
 * <p><b>The window has to be a real fraction of a game tick to be worth anything.</b> Aggregation only pays
 * when several members' updates land in the same window, and members tick independently — so the number of
 * updates a window collects is roughly {@code members × window / tick}. At 100ms against a 600ms tick that
 * is 0.83, and a measured run bore it out exactly: 1.1 updates per frame, a 15% saving bought with four
 * times the relay latency. 300ms collects around half the room instead.
 *
 * <p>So the cost is deliberate and it is latency: up to a window, roughly half a tick. That is affordable
 * only because the data being delayed is itself only produced once a tick — a peer holds any given value
 * for the rest of the tick regardless of when in it the value arrived. Pings, ready checks and spec drains
 * are <em>not</em> collected here: those are one-off, reaction-timed, and want to be prompt.
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

	@Scheduled(fixedRateString = "${app.party-v2.aggregate-ms:300}")
	void flush() {
		manager.flushRooms();
	}
}

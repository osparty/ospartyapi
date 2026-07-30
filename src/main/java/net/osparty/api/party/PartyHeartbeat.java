package net.osparty.api.party;

import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps this node's ownership alive (PARTY_V2_MIGRATION.md §10) and drains its rooms on the way out.
 *
 * <p>Every renewal bumps the {@code pv2:owner:} lock + {@code pv2:party:} hash TTLs for each room owned
 * here. If a renewal reports the lock is gone, another node has claimed the room and this one stops serving
 * it immediately (§16 R5). If this node dies outright it simply stops renewing, the locks expire, and the
 * rooms become claimable by whichever node the clients reconnect to.
 *
 * <p>Renews well inside the 30 s TTL so a transient Redis blip doesn't drop a still-live owner. Skipped in
 * the {@code test} profile (single-node ownership never expires).
 *
 * <p>The same schedule also runs the reclaim scan: rooms whose owner died without draining are taken over
 * here, so a party has a settled destination to reconnect to rather than one decided by whichever client
 * happens to re-host first.
 */
@Component
@Profile("!test")
public class PartyHeartbeat implements SmartLifecycle {
	private static final Logger log = LoggerFactory.getLogger(PartyHeartbeat.class);

	/** Time allowed for drain frames to reach clients before the web server starts closing sockets. */
	private static final long DRAIN_FLUSH_MS = 250;

	private final PartyManager manager;
	private volatile boolean running;

	public PartyHeartbeat(PartyManager manager) {
		this.manager = manager;
	}

	@Scheduled(fixedRate = 10_000)
	void renewOwned() {
		// Copy the ids: draining a lost room mutates the live room map.
		for (String room : new ArrayList<>(manager.ownedRoomIds())) {
			if (!manager.renew(room)) {
				// Our lock expired and another node claimed the room; stop serving it and send the members
				// off to rebuild it on the new owner.
				log.warn("Party: lost ownership of {}, draining", room);
				manager.recordFailover();
				manager.drain(room, false);
				continue;
			}
			// Still ours: drop any member whose socket died without the close callback firing, and discard
			// the room if that leaves it hostless. Nothing else prunes membership, so a missed callback
			// otherwise strands a room here for the life of the node.
			manager.pruneRoom(room);
		}
		// Unconditional, and after the drains so it reports what this node is actually still carrying. An
		// idle node has to publish a load of zero or it never appears as a placement candidate — which is
		// exactly how one node ends up owning every party.
		manager.publishLoad();
	}

	/**
	 * Take over rooms whose owner died without draining (PARTY_V2_MIGRATION.md §10). Every node scans, and
	 * the {@code SET NX} decides — so the room gets exactly one new owner however many nodes are looking.
	 *
	 * <p>Skipped once this node is shutting down: {@link #stop} has just handed its rooms over, and a scan
	 * running between that and process exit would claim them straight back onto a node that is leaving.
	 */
	@Scheduled(fixedRate = 30_000)
	void reclaimExpired() {
		if (!running) {
			return;
		}
		manager.reclaimExpired();
	}

	@Override
	public void start() {
		running = true;
	}

	/**
	 * Graceful drain on shutdown (PARTY_V2_MIGRATION.md §16 R4): release each owned room's lock and tell its
	 * members to reconnect, so parties migrate to another node *before* this one goes away rather than
	 * waiting out the lock TTL.
	 *
	 * <p>This is a {@link SmartLifecycle} rather than a {@code @PreDestroy} because of ordering: by
	 * destruction time Tomcat has already shut down (so the drain frames reach nobody) and the Redis
	 * connection factory is closed (so the lock release silently fails). Running in the highest phase stops
	 * this bean before the web server and before any connection pools are torn down.
	 */
	@Override
	public void stop() {
		running = false;
		// Before anything else: a node that is going away must stop being a placement candidate, or it will
		// be handed new parties during the very window it is draining the ones it has.
		manager.retireLoad();
		java.util.List<String> owned = new ArrayList<>(manager.ownedRoomIds());
		if (owned.isEmpty()) {
			return;
		}
		for (String room : owned) {
			log.info("Party: draining {} on shutdown", room);
			manager.drain(room, true);
		}
		try {
			// Give the drain frames a moment on the wire; the sockets are about to be closed under us.
			Thread.sleep(DRAIN_FLUSH_MS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** Highest phase: stopped first, while the sockets and Redis are both still usable. */
	@Override
	public int getPhase() {
		return Integer.MAX_VALUE;
	}
}

package net.osparty.api.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns this node's live party rooms in memory (PARTY_V2_MIGRATION.md §11). The top-level map is
 * concurrent and each {@link LivePartyRoom} guards its own state, so rooms are independent.
 *
 * <p>P2: a room is created here only after this node wins the {@link PartyOwnershipService} claim, so a host
 * whose room is already owned elsewhere is redirected rather than served. Ownership is renewed by
 * {@link PartyV2Heartbeat}; a discarded room releases its lock.
 */
@Component
public class PartyV2Manager {
	private static final Logger log = LoggerFactory.getLogger(PartyV2Manager.class);

	private final ObjectMapper mapper;
	private final PartyOwnershipService ownership;
	private final NodeIdentity node;
	private final PartyV2Bus bus;
	private final NodeLoadRegistry load;
	/**
	 * How long a member may go without sending anything before it is treated as gone. Generous next to the
	 * plugin's heartbeat (every five seconds when it has nothing else to say) — this is the backstop for connections
	 * nothing else reports as dead, not a tight liveness check.
	 */
	private final long memberTimeoutMs;
	private final Map<String, LivePartyRoom> rooms = new ConcurrentHashMap<>();
	private final AtomicLong memberIds = new AtomicLong();
	private final java.util.concurrent.atomic.LongAdder redirects = new java.util.concurrent.atomic.LongAdder();
	private final java.util.concurrent.atomic.LongAdder failovers = new java.util.concurrent.atomic.LongAdder();
	private final java.util.concurrent.atomic.LongAdder ownerPending = new java.util.concurrent.atomic.LongAdder();
	private final java.util.concurrent.atomic.LongAdder reclaims = new java.util.concurrent.atomic.LongAdder();
	private final java.util.concurrent.atomic.LongAdder rebalances = new java.util.concurrent.atomic.LongAdder();
	private final java.util.concurrent.atomic.LongAdder pruned = new java.util.concurrent.atomic.LongAdder();

	public PartyV2Manager(ObjectMapper mapper, PartyOwnershipService ownership, NodeIdentity node,
		PartyV2Bus bus, NodeLoadRegistry load,
		@org.springframework.beans.factory.annotation.Value("${app.party-v2.member-timeout-ms:90000}")
		long memberTimeoutMs) {
		this.mapper = mapper;
		this.ownership = ownership;
		this.node = node;
		this.bus = bus;
		this.load = load;
		this.memberTimeoutMs = memberTimeoutMs;
		// Control signals from other nodes act on the rooms held here, so the bus calls back into us.
		bus.setListener(new PartyV2Bus.Listener() {
			@Override
			public void onOwnerChanged(String room, String nodeId) {
				ownerChangedElsewhere(room, nodeId);
			}

			@Override
			public void onForceReconnect(String room) {
				forceReconnect(room);
			}
		});
	}

	/**
	 * Another node claimed a room. If we were still serving it, we have lost it — drain now rather than
	 * waiting for the next renewal to fail, which is up to a full heartbeat interval of members sending
	 * state to a node that no longer owns their party.
	 */
	private void ownerChangedElsewhere(String room, String nodeId) {
		if (!rooms.containsKey(room)) {
			return;
		}
		log.info("Party V2: {} claimed by {}, draining here", room, nodeId);
		recordFailover();
		// The lock is already theirs — releasing it would delete a lock we no longer hold.
		drain(room, false);
	}

	/** Operator-driven: stop serving a room and send its members to reconnect wherever they land. */
	private void forceReconnect(String room) {
		if (!rooms.containsKey(room)) {
			return;
		}
		log.info("Party V2: force-reconnect for {}", room);
		drain(room, true);
	}

	/** A fresh, process-unique member id assigned to a connecting client. */
	long nextMemberId() {
		return memberIds.incrementAndGet();
	}

	/** This node's id (the node-hint returned to clients on {@code welcome}/{@code redirect}). */
	String nodeId() {
		return node.nodeId();
	}

	/**
	 * Where a new room should be created: empty to host it here, or the id of a node the host should be
	 * redirected to because this one is carrying materially more load.
	 *
	 * <p>Placement is a one-time decision. A client is pinned to its party's owner for the life of the room,
	 * and its node-hint outlives the party — so without this, a room lands on whichever node its host's
	 * socket happened to be on, which is biased by every redirect that client has taken since it started.
	 * That bias is one-way (joiners are pulled to owners, never pushed back), so a single node accumulates
	 * hosts indefinitely.
	 *
	 * <p>Only ever consulted for a room that does not exist yet. An existing room's placement is settled by
	 * ownership, and one mid-handover belongs to whoever re-claims it — rebalancing either would bounce a
	 * live party between nodes for the sake of a number.
	 */
	Optional<String> placementFor(String id) {
		if (rooms.containsKey(id)) {
			return Optional.empty();
		}
		if (ownership.lookup(id).isPresent() || ownership.handoverPending(id)) {
			return Optional.empty();
		}
		return load.preferredHost(connectedMembers());
	}

	/** Publish this node's load for placement, and refresh its view of its peers'. Called on the heartbeat. */
	void publishLoad() {
		load.publish(connectedMembers());
	}

	/** Withdraw this node from placement, so a node on its way out stops attracting new parties. */
	void retireLoad() {
		load.retire();
	}

	/**
	 * Claim ownership of {@code id} for this node and return its room, creating it on first host. Returns
	 * null if the room is owned by another node — the caller must redirect the host there.
	 */
	LivePartyRoom hostRoom(String id, String activityId) {
		LivePartyRoom existing = rooms.get(id);
		if (existing != null) {
			return existing;
		}
		PartyOwnershipService.Claim claim = ownership.claim(id);
		if (claim == PartyOwnershipService.Claim.OWNED_BY_OTHER) {
			return null;
		}
		if (claim == PartyOwnershipService.Claim.CLAIMED) {
			// A fresh claim may be a takeover from a node that died holding this room. Tell the cluster, so
			// a node still serving it stops now instead of on its next failed renewal.
			bus.publishOwnerChanged(id, node.nodeId());
		}
		return rooms.computeIfAbsent(id, k -> new LivePartyRoom(id, activityId, node.nodeId(), mapper));
	}

	/**
	 * Drop ghost members from {@code id} — sockets that closed without the container reporting it — and
	 * discard the room if that leaves it hostless or empty.
	 *
	 * <p>Membership is otherwise pruned only by the close callback, so a single missed callback strands a
	 * room forever: it never empties, so it is never discarded, so the heartbeat renews its ownership lock
	 * for the life of the node. Sweeping on the same schedule that renews those locks means a leak can
	 * outlive its clients by at most one heartbeat.
	 */
	void pruneRoom(String id) {
		LivePartyRoom room = rooms.get(id);
		if (room == null) {
			return;
		}
		LivePartyRoom.Prune prune = room.pruneClosed(memberTimeoutMs);
		if (prune.removed() == 0) {
			return;
		}
		pruned.add(prune.removed());
		log.info("Party V2: pruned {} closed session(s) from {}", prune.removed(), id);
		if (prune.discard()) {
			log.info("Party V2: discarding {} — nothing left after the sweep", id);
			discard(id);
		}
	}

	/**
	 * Send every owned room the live updates it collected this window (see {@link PartyV2Aggregator}).
	 *
	 * <p>Walks all rooms rather than tracking which have something pending: the check is an empty-list read
	 * under the room's own lock, and a set of dirty rooms would need maintaining on the hottest path in the
	 * system to save it.
	 *
	 * <p>{@code idleMs} is how long a room with nothing urgent may hold its updates; zero flushes every room
	 * on every call, which is what the tests want.
	 */
	void flushRooms() {
		flushRooms(0);
	}

	void flushRooms(long idleMs) {
		for (LivePartyRoom room : rooms.values()) {
			try {
				room.flush(idleMs);
			}
			catch (Exception e) {
				// One room's bad send must not stop the rest of this node's parties from being flushed.
				log.debug("Party V2: flush failed for {}: {}", room.id, e.toString());
			}
		}
	}

	/** Note inbound traffic from a seated member, so the sweep can tell it apart from a ghost. */
	void touch(String roomId, long memberId) {
		LivePartyRoom room = rooms.get(roomId);
		if (room != null) {
			room.touch(memberId);
		}
	}

	/** The existing room for {@code id}, or null if none is hosted here. */
	LivePartyRoom room(String id) {
		return rooms.get(id);
	}

	/** The node owning {@code id} cluster-wide (for redirecting a joiner), or empty if unknown. */
	Optional<PartyOwnershipService.Owner> owner(String id) {
		return ownership.lookup(id);
	}

	/** Discard a room once it's empty or its host has left, releasing this node's ownership lock. */
	void discard(String id) {
		if (rooms.remove(id) != null) {
			ownership.release(id);
		}
	}

	/**
	 * Whether this node still owns {@code id}. Checked before authoritative actions (admit/kick/capacity/
	 * host transfer) so a node that lost its lock can't keep mutating a room another node now owns
	 * (PARTY_V2_MIGRATION.md §16 R5). Deliberately not on the live-state hot path.
	 */
	boolean ownsRoom(String id) {
		return ownership.ownedBySelf(id);
	}

	/** Renew ownership of {@code id}; false means the lock was lost and the room must be drained. */
	boolean renew(String id) {
		return ownership.renew(id);
	}

	/**
	 * Stop serving a room: tell its members to reconnect elsewhere, then drop it. Used on shutdown and when
	 * ownership is lost. The lock is only released when we still hold it — a lost room already has a new
	 * owner whose lock must not be deleted.
	 */
	void drain(String id, boolean releaseLock) {
		LivePartyRoom room = rooms.remove(id);
		if (room == null) {
			return;
		}
		room.broadcastOwnerChanged();
		if (releaseLock) {
			// A handover, not an ending: the room's metadata stays behind so members that reconnect ahead
			// of their host are told to retry rather than that the party is gone.
			ownership.releaseForHandover(id);
		}
	}

	/** Whether {@code id} is mid-handover — no owner, but owned within the grace window. */
	boolean handoverPending(String id) {
		return ownership.handoverPending(id);
	}

	/**
	 * Take ownership of rooms whose owner died without draining (PARTY_V2_MIGRATION.md §10), and announce
	 * each takeover on the bus.
	 *
	 * <p>No {@link LivePartyRoom} is created: the live state died with the old owner, and an empty room here
	 * would be renewed by the heartbeat forever whether or not anyone came back for it. Holding only the
	 * lock makes this node the room's single answer to {@code lookup} — the host is redirected here and
	 * rebuilds the room with its {@code host} frame, members are told to retry until it does — and if
	 * nobody returns, the unrenewed lock simply expires again.
	 *
	 * @return the rooms this node took over.
	 */
	Set<String> reclaimExpired() {
		Set<String> claimed = ownership.reclaimExpired();
		for (String room : claimed) {
			reclaims.increment();
			log.info("Party V2: reclaimed {} from an expired owner", room);
			bus.publishOwnerChanged(room, node.nodeId());
		}
		return claimed;
	}

	/** Ids of the rooms this node currently owns (for heartbeat renewal). */
	Set<String> ownedRoomIds() {
		return rooms.keySet();
	}

	public int roomCount() {
		return rooms.size();
	}

	/** Members connected to rooms owned by this node (metrics). */
	public int connectedMembers() {
		int total = 0;
		for (LivePartyRoom room : rooms.values()) {
			total += room.memberCount();
		}
		return total;
	}

	// Counters are held here (rather than injected meters) so the manager stays trivially constructible
	// in tests; PartyV2MetricsConfig binds them to Micrometer.
	void recordRedirect() {
		redirects.increment();
	}

	void recordFailover() {
		failovers.increment();
	}

	void recordOwnerPending() {
		ownerPending.increment();
	}

	void recordRebalance() {
		rebalances.increment();
	}

	public double redirectCount() {
		return redirects.sum();
	}

	public double failoverCount() {
		return failovers.sum();
	}

	public double ownerPendingCount() {
		return ownerPending.sum();
	}

	public double reclaimCount() {
		return reclaims.sum();
	}

	public double rebalanceCount() {
		return rebalances.sum();
	}

	public double prunedCount() {
		return pruned.sum();
	}
}

package net.osparty.api.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2Manager {
	private final ObjectMapper mapper;
	private final PartyOwnershipService ownership;
	private final NodeIdentity node;
	private final Map<String, LivePartyRoom> rooms = new ConcurrentHashMap<>();
	private final AtomicLong memberIds = new AtomicLong();
	private final java.util.concurrent.atomic.LongAdder redirects = new java.util.concurrent.atomic.LongAdder();
	private final java.util.concurrent.atomic.LongAdder failovers = new java.util.concurrent.atomic.LongAdder();

	public PartyV2Manager(ObjectMapper mapper, PartyOwnershipService ownership, NodeIdentity node) {
		this.mapper = mapper;
		this.ownership = ownership;
		this.node = node;
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
	 * Claim ownership of {@code id} for this node and return its room, creating it on first host. Returns
	 * null if the room is owned by another node — the caller must redirect the host there.
	 */
	LivePartyRoom hostRoom(String id, String activityId) {
		LivePartyRoom existing = rooms.get(id);
		if (existing != null) {
			return existing;
		}
		if (ownership.claim(id) == PartyOwnershipService.Claim.OWNED_BY_OTHER) {
			return null;
		}
		return rooms.computeIfAbsent(id, k -> new LivePartyRoom(id, activityId, mapper));
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
			ownership.release(id);
		}
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

	public double redirectCount() {
		return redirects.sum();
	}

	public double failoverCount() {
		return failovers.sum();
	}
}

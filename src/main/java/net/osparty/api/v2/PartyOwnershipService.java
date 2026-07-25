package net.osparty.api.v2;

import java.util.Optional;
import java.util.Set;

/**
 * Party V2 ownership: which node owns a given live-party room (PARTY_V2_MIGRATION.md §5/§10). A room is
 * owned by exactly one node at a time; ownership is an atomic {@code SET NX} lock in the {@code pv2:}
 * keyspace with a short TTL, so a dead owner's lock expires and the room is free to be re-claimed.
 *
 * <p>Failover in P2 is client-driven: when the owner dies its lock expires, and the reconnecting host's
 * next {@code host} frame re-claims the now-free room ({@link #claim} returns {@link Claim#CLAIMED}).
 * Interfaces are Redis-agnostic so tests use {@link LocalPartyOwnershipService} (single-node, always owns).
 */
public interface PartyOwnershipService {
	enum Claim { CLAIMED, ALREADY_OWNED_BY_SELF, OWNED_BY_OTHER }

	/** The owning node of a room, or the caller's identity for {@code ALREADY_OWNED_BY_SELF}. */
	record Owner(String nodeId) {
	}

	/** Atomically claim {@code room} for this node ({@code SET pv2:owner:{room} nodeId NX EX}). */
	Claim claim(String room);

	/** The node that currently owns {@code room}, or empty if none does. */
	Optional<Owner> lookup(String room);

	/**
	 * Refresh this node's ownership lock + party metadata TTL.
	 *
	 * @return true if this node still held the lock; false means ownership was lost (our lock expired and
	 *     another node claimed the room), so the caller must stop serving it.
	 */
	boolean renew(String room);

	/** Whether this node currently holds {@code room}'s lock — the fence for authoritative actions (R5). */
	boolean ownedBySelf(String room);

	/** Release ownership of {@code room} (only if we still hold it), discarding its metadata with it. */
	void release(String room);

	/**
	 * Release ownership as part of a handover: drop this node's lock but leave the room's metadata behind
	 * for a short grace window. Used when draining rather than ending a room, so that the members — which
	 * reconnect at the same moment as their host, but have less work to do before they arrive — can be told
	 * to retry instead of being told the party no longer exists (PARTY_V2_MIGRATION.md §16 R4).
	 */
	void releaseForHandover(String room);

	/**
	 * Whether {@code room} has no owner but was owned within the grace window — a handover is in flight and
	 * the host has yet to re-claim it. Only meaningful when {@link #lookup} came back empty.
	 */
	boolean handoverPending(String room);

	/**
	 * Claim every room whose metadata is still present but whose owner lock has expired — its owner died
	 * without draining (PARTY_V2_MIGRATION.md §10). Every node runs this scan and races on the same
	 * {@code SET NX}, so exactly one wins each room.
	 *
	 * <p>Winning makes this node the room's answer to {@code lookup} — a fixed destination for the host and
	 * members to reconnect to, instead of each of them racing to claim it from wherever they happen to
	 * land. The room's live state is not restored and cannot be: it lived only in the dead node's memory.
	 * The clients bring it back when they arrive.
	 *
	 * @return the rooms newly claimed by this node.
	 */
	Set<String> reclaimExpired();
}

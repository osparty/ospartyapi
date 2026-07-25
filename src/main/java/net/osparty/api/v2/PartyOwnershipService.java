package net.osparty.api.v2;

import java.util.Optional;

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

	/** Refresh this node's ownership lock + party metadata TTL. No-op if we no longer own the room. */
	void renew(String room);

	/** Release ownership of {@code room} (only if we still hold it). */
	void release(String room);
}

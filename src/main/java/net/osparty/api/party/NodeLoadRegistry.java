package net.osparty.api.party;

import java.util.Optional;

/**
 * Per-node load, published so a new party can be placed on a node with room for it rather than on whichever
 * one its host happened to be connected to (PARTY_V2_MIGRATION.md §3.2).
 *
 * <p>Node affinity pins a client to its party's owner, so placement is decided exactly once — when the room
 * is created — and never revisited: moving a live room would mean migrating its in-memory state and roster.
 * Getting that single decision right is therefore the whole of the load balancing. Without it, a room lands
 * wherever its host's socket happened to be, which is biased by every redirect that client has ever taken.
 *
 * <p>Redis-agnostic so tests use {@link LocalNodeLoadRegistry} (single node, always hosts locally).
 */
public interface NodeLoadRegistry {
	/** Publish this node's current member count, and refresh its cached view of its peers'. */
	void publish(int members);

	/** Withdraw this node from placement. Called on shutdown: a draining node must not attract new parties. */
	void retire();

	/**
	 * A node better placed than this one to own a new room, or empty to host it here.
	 *
	 * @param selfMembers this node's current member count, passed in rather than read back from the shared
	 *     view because it is the one figure that is always current.
	 */
	Optional<String> preferredHost(int selfMembers);
}

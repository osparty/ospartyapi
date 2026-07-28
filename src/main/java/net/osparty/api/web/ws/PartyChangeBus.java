package net.osparty.api.web.ws;

import java.util.function.BiConsumer;

/**
 * Tells every node which advertisement just changed.
 *
 * <p>The board is reconciled by polling: each node reads every ad from Redis, deserialises it, copies it
 * and diffs the result against what it last saw. That runs on every node, on a timer, whether or not
 * anything happened — so a quiet board costs exactly as much as a busy one, and the cost multiplies by
 * replica count. What actually changes in a tick is a handful of ads out of thousands.
 *
 * <p>Every mutation already goes through one place, so it can simply say so. A node that hears about an ad
 * re-reads <em>that</em> ad and nothing else. The poll stays as a backstop, because the one change nobody
 * can publish is an ad expiring on its own TTL, and because a missed message must not strand the board.
 *
 * <p>Only the id travels. The alternative — putting the whole advertisement on the bus — would save the
 * single read that follows, at the cost of a second serialised representation of the model to keep in step
 * with the first, forever. A GET per change is cheap; a duplicated schema is not.
 */
public interface PartyChangeBus {
	/**
	 * Announce that {@code partyId} was created, updated or removed. Never throws.
	 *
	 * <p>{@code seq} is the revision the change was written at, so that every node records the same one.
	 * A removal has no advertisement left to carry it, so the node that noticed allocates it and everyone
	 * else takes the number rather than inventing their own — otherwise the same disappearance would be
	 * ordered differently on each node and a resuming client could miss it on one and not another.
	 */
	void publish(String partyId, long seq);

	/** Called on every node, including the one that published, with the id and revision that changed. */
	void setListener(BiConsumer<String, Long> listener);
}

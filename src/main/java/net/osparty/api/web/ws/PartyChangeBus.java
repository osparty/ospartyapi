package net.osparty.api.web.ws;

import java.util.function.Consumer;

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
	/** Announce that {@code partyId} was created, updated or removed. Never throws. */
	void publish(String partyId);

	/** Called on every node, including the one that published, with the id that changed. */
	void setListener(Consumer<String> listener);
}

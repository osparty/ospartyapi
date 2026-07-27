package net.osparty.api.v2;

/**
 * One client's live-party connection, as much of it as the room layer needs to know.
 *
 * <p>V2 exists to move live state off a relay this project does not own, and the measurements say the cost
 * of doing that is the send itself — Tomcat's send path is roughly half of this service's CPU
 * (PARTY_V2_OPTIMIZATION.md §6.5.3). That makes the transport a thing worth replacing, and replacing it is
 * only safe if the rooms, the ownership and the placement logic never knew which transport they were on.
 * Hence this: {@link LivePartyRoom} and {@link PartyV2FrameHandler} speak to sessions, not to servlets.
 */
public interface PartySession {
	/** Stable per-connection id; the frame handler keys its per-connection context on it. */
	String id();

	boolean isOpen();

	/**
	 * Write one frame. Implementations must not throw for an ordinary send failure — a peer whose connection
	 * broke is dropped by the close callback and the ghost sweep, not by an exception unwinding through
	 * another member's fan-out.
	 */
	void send(String json);

	void close();

	/**
	 * Whether the client dialled the {@code /n/{nodeId}} form, meaning it was sent to this node deliberately
	 * — by a redirect, or by resolving the owner up front. Placement must not second-guess that, or two
	 * nodes on slightly different load snapshots can pass the same host back and forth.
	 */
	boolean nodeHinted();
}

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
	 * Write one frame: UTF-8 JSON, sent as a binary WebSocket message.
	 *
	 * <p>Binary rather than text because a text frame is encoded per recipient — {@code sendPartialString}
	 * was 22% of this service's CPU doing nothing but UTF-8 — while bytes that Jackson already produced can
	 * be handed to the socket as they are. The payload is the same JSON either way; only the opcode differs.
	 *
	 * <p>Implementations must not throw for an ordinary send failure — a peer whose connection broke is
	 * dropped by the close callback and the ghost sweep, not by an exception unwinding through another
	 * member's fan-out.
	 */
	void send(byte[] frame);

	void close();

	/**
	 * Whether the client dialled the {@code /n/{nodeId}} form, meaning it was sent to this node deliberately
	 * — by a redirect, or by resolving the owner up front. Placement must not second-guess that, or two
	 * nodes on slightly different load snapshots can pass the same host back and forth.
	 */
	boolean nodeHinted();
}

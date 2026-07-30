package net.osparty.api.transport;

/**
 * One client's connection, as much of it as the protocols above need to know. Both of them — the ad board
 * and the live party — talk to one of these, which is what lets them share a socket.
 *
 * <p>The live party exists to move live state off a relay this project does not own, and the measurements
 * say the cost of doing that is the send itself — Tomcat's send path was roughly half of this service's CPU
 * (PARTY_V2_OPTIMIZATION.md §6.5.3). That made the transport a thing worth replacing, and replacing it was
 * only safe because the rooms, the ownership and the placement logic never knew which transport they were
 * on. Hence this: they speak to sessions, not to servlets.
 */
public interface SocketSession {
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

	/**
	 * Write one frame as text. The live party has no use for this — it speaks binary in both directions —
	 * but the ad board answers clients that predate compression, and those expect text frames.
	 */
	void sendText(String json);

	void close();

	/**
	 * Whether the client dialled the {@code /n/{nodeId}} form, meaning it was sent to this node deliberately
	 * — by a redirect, or by resolving the owner up front. Placement must not second-guess that, or two
	 * nodes on slightly different load snapshots can pass the same host back and forth.
	 */
	boolean nodeHinted();
}

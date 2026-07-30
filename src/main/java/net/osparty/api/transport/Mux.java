package net.osparty.api.transport;

/**
 * The one-byte channel tag that lets a single connection carry both protocols.
 *
 * <p>A client used to hold two sockets — one on the ad board, one on the live party — which doubled the
 * connection count at the ingress for everybody actually in a party. The gateway costs about as much CPU per
 * socket as the API pods do (PARTY_V2_OPTIMIZATION.md), so halving sockets is worth more there than
 * anywhere else. The merged endpoint carries both, and every frame on it — in both directions — begins with
 * one of these bytes.
 *
 * <p>A tag rather than a frame-type namespace because the two protocols already disagree: {@code host},
 * {@code update} and {@code transferHost} all exist in both and mean different things. Routing on a leading
 * byte also decides before anything is parsed, which is the point — the demultiplexer never touches JSON.
 *
 * <p>Frames on the merged endpoint are always binary, including the board's uncompressed ones, since a text
 * frame has nowhere to put the tag.
 */
public final class Mux {
	/** The advertisement board: search, hosting, invites, Discord. */
	public static final byte BOARD = 1;
	/** The live party: roster, member state, pings, ready checks. */
	public static final byte LIVE = 2;

	private Mux() {
	}
}

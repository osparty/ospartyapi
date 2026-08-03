package net.osparty.api.party;

/**
 * Inter-node control signals for the live party (PARTY_V2_MIGRATION.md §3.3, §4.1). Ownership moves between
 * nodes, but the node losing a room is not always the node that notices — a claim happens on the winner,
 * while the members are still connected to the loser. This bus carries that news directly instead of
 * waiting for the loser's next heartbeat to fail, which is up to a full renewal interval away.
 *
 * <p>Same shape as {@link net.osparty.api.web.ws.InviteBus}: a publish side, and a listener the node's
 * room manager registers so signals about rooms it actually serves reach it. Signals about rooms this node
 * does not serve are simply dropped — every node hears everything, and most of it is not theirs.
 *
 * <p>Deliberately <em>not</em> implemented here: §3.2's fallback inter-node frame forwarding. That exists
 * for gateways which cannot path-route, and this cluster's ingress routes {@code /n/{nodeId}} to the exact
 * pod, so a non-owner never has to relay a member's frames — it redirects instead.
 */
public interface PartyBus {
	/**
	 * Announce that {@code nodeId} has claimed {@code room}. Any node still serving that room has lost it
	 * and must drain, sending its members off to reconnect to the new owner.
	 */
	void publishOwnerChanged(String room, String nodeId);

	/**
	 * Ask whichever node serves {@code room} to let it go and send its members to reconnect — an operator
	 * lever for moving a party off a node without killing the pod.
	 */
	void publishForceReconnect(String room);

	/** Register the callback that reacts to signals from other nodes. */
	void setListener(Listener listener);

	/** What a node does about a signal concerning a room it may be serving. */
	interface Listener {
		/** {@code nodeId} claimed {@code room}; if we still serve it, we no longer own it. */
		void onOwnerChanged(String room, String nodeId);

		/** Drop {@code room} and send its members to reconnect elsewhere. */
		void onForceReconnect(String room);
	}
}

package net.osparty.api.party;

/**
 * The board's advertisements, as far as the party module needs them: a host's ad and the room it points at
 * are two halves of the same party, and only one of them notices when the host stops being there.
 *
 * <p>An ad stays alive for as long as its host's connection does — the board renews its TTL on a timer for
 * every session it has bound. A room, on the other hand, is kept alive by traffic
 * ({@link PartyRoom#pruneDead}), because a connection that reports open proves nothing. Those two rules
 * disagree for exactly one client: one that is still connected but has stopped sending — the plugin's
 * heartbeat runs off the game's tick, so a player who logs out with the client open goes silent while the
 * socket stays up. The room is swept, the ad is renewed, and the board is left advertising a party whose
 * room no longer exists.
 *
 * <p>This is the party module's way of saying so. An interface rather than a direct call for the reason
 * {@link PartyBus} and {@link NodeLoadRegistry} are: rooms know about sessions and about each other, never
 * about the transport or the board.
 */
public interface HostedAds {
	/**
	 * Remove the advertisement {@code sessionId} hosts, if it still holds one, and tell that session it is
	 * gone so a client which is merely idle folds its hosting state rather than advertising into a void.
	 *
	 * <p>A no-op for a session the board has already unbound, which is what makes this safe to call for
	 * every swept host: one whose socket closed cleanly was unbound when it closed.
	 */
	void dropHostedBy(String sessionId);
}

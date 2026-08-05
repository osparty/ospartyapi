package net.osparty.api.party;

/**
 * Who this service has decided may take a seat in a room without the host being asked again.
 *
 * <p><b>Why this exists.</b> A joiner used to be auto-admitted on the strength of {@code invited:true} in its
 * own join frame -- a claim the server took on trust, which made admission to any party a matter of asserting
 * it. Both things that legitimately produce that flag are decisions <em>this service</em> made, so it does not
 * have to ask the client: it can remember them and check the joiner against its own record.
 *
 * <p>Two sources, and the second is the one that carries the traffic:
 * <ul>
 *   <li>an invite the board delivered, which {@code BoardBroadcaster.handleInvite} already checks came from
 *       the host or an admitted member;</li>
 *   <li>an admission the host granted, so that a member which is <em>already in the party</em> is seated
 *       straight back into it. A room rebuilt on a new owner seats everyone from scratch, and without this
 *       every handover would tip the whole party into the host's applicant queue to be re-admitted one by
 *       one.</li>
 * </ul>
 *
 * <p><b>Why a shared service and not a field on the room.</b> Invites arrive on the board socket and joins on
 * the party socket, and a handover moves the room to a different pod entirely -- the case this exists to
 * cover is precisely the one where the old room object is gone. The grant has to outlive it, which is what
 * {@link RedisPartyAdmissionService} is for.
 *
 * <p><b>Why the room key and not the advertisement id.</b> The board knows a party by ad id; the live party
 * knows it by room key, which is the ad's passphrase. Only the board holds both, so it writes the grant under
 * the key the party side will ask by.
 *
 * <p><b>Why a check rather than a one-shot consume.</b> A joiner presents its claim more than once by design
 * -- the retry loop for a room mid-handover does exactly that, several times, before it is ever seated.
 * Expiry bounds the window instead; see {@link #TTL}.
 */
public interface PartyAdmissionService {
	/**
	 * How long a grant stays good for. It has to outlast a node handover plus the client's reconnect backoff
	 * with room to spare, and outlast someone alt-tabbing back to the game before clicking join. Past that a
	 * grant is a standing key to a party, so it is deliberately not generous.
	 */
	java.time.Duration TTL = java.time.Duration.ofMinutes(15);

	/** Record that {@code name} may take a seat in {@code room}. Taken as given, not pre-normalised. */
	void grant(String room, String name);

	/** Whether {@code name} holds an unexpired grant for {@code room}. False for anything unknown or absent. */
	boolean isGranted(String room, String name);
}

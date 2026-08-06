package net.osparty.api.party.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.util.TokenBuffer;

/**
 * A decoded client → server live-party frame. Only the fields relevant to {@link #type} are populated; the
 * rest are null (the client omits them). See PARTY_V2_MIGRATION.md §8.
 *
 * <p>{@link #state} and {@link #meta} are opaque to the server — the member's live self-snapshot (a
 * serialised plugin {@code PlayerUpdate}) and the host's advertised party settings respectively, which the
 * owner node stores and relays verbatim without interpreting.
 *
 * <p>{@link #urgent} is the one thing the server is told <em>about</em> a live update, and it is a field of
 * the frame rather than of the payload for exactly that reason: the owner still never reads the state, it
 * only learns whether the sender wants it delivered promptly (see {@code PartyRoom#flush}).
 *
 * <p>The three keys that ride on every live frame are one character each. The payload inside {@code state}
 * was shortened first and the wrapper then outweighed it — at two updates per aggregated frame the envelope
 * was over half the bytes. The rest keep their names: they belong to frames sent once per party, where a
 * readable wire is worth more than the handful of bytes.
 */
public record Inbound(
	@JsonProperty("t") String type,
	String room,
	Long accountHash,
	String name,
	String hostName,
	String activityId,
	Integer capacity,
	Boolean locked,
	String role,
	Boolean learner,
	Boolean teacher,
	Boolean invited,
	@JsonProperty("s") @JsonDeserialize(using = RawJsonDeserializer.class) TokenBuffer state,
	@JsonProperty("g") Boolean urgent,
	Integer x,
	Integer y,
	Integer plane,
	Integer color,
	String action,
	Long target,
	String url,
	Long checkId,
	String starter,
	String kind,
	String friendsChat,
	Integer npcIndex,
	String weapon,
	Integer hit,
	Integer world,
	String newHostKey,
	String newHostName,
	Boolean hostStays,
	JsonNode meta) {

	/**
	 * Fold "no account" to null however the client spells it.
	 *
	 * <p>The plugin sends {@code -1} when nobody is logged in, because that is what
	 * {@code Client.getAccountHash()} returns; this service documents {@code 0} as unknown and every check
	 * downstream is written as {@code != 0}. Left alone, {@code -1} passes all of them, and every logged-out
	 * client in the world shares one "known" identity -- they collide in the block list, in player flags, in
	 * party history, and in the account index invites are routed by.
	 *
	 * <p>Normalising here rather than at each use means a check added later cannot miss the case. Null, not
	 * zero, because {@code null} is what the rest of this record already means by "not supplied", and the two
	 * spellings should not survive past decoding.
	 */
	public Inbound {
		if (accountHash != null && (accountHash == -1L || accountHash == 0L)) {
			accountHash = null;
		}
	}
}

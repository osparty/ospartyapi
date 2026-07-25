package net.osparty.api.v2.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A decoded client → server Party V2 frame. Only the fields relevant to {@link #type} are populated; the
 * rest are null (the client omits them). See PARTY_V2_MIGRATION.md §8.
 *
 * <p>{@link #state} is opaque to the server — it is the member's live self-snapshot (a serialised plugin
 * {@code PlayerUpdate}) which the owner node stores and relays verbatim without interpreting.
 */
public record Inbound(
	String type,
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
	JsonNode state,
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
	Boolean hostStays) {
}

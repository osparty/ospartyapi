package net.osparty.api.v2.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A server → client Party V2 frame. Null fields are omitted on the wire. See PARTY_V2_MIGRATION.md §8.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Outbound(
	String type,
	Long memberId,
	String status,
	String host,
	Integer capacity,
	Boolean locked,
	Boolean closed,
	String discordUrl,
	List<RosterEntry> members,
	JsonNode state,
	Integer x,
	Integer y,
	Integer plane,
	Integer color,
	String name,
	String detail,
	String nodeId) {

	/** Assigned identity + role on (re)join; followed by a {@code roster} and the current member states. */
	public static Outbound welcome(long memberId, String status) {
		return new Outbound("welcome", memberId, status, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null);
	}

	/** The server-authoritative roster and room meta. Re-sent on any membership/status/meta change. */
	public static Outbound roster(String host, int capacity, boolean locked, boolean closed,
		String discordUrl, List<RosterEntry> members) {
		return new Outbound("roster", null, null, host, capacity, locked, closed, discordUrl, members, null,
			null, null, null, null, null, null, null);
	}

	/** A peer's live snapshot, relayed verbatim ({@code state} is the opaque payload they sent). */
	public static Outbound memberState(long memberId, JsonNode state) {
		return new Outbound("memberState", memberId, null, null, null, null, null, null, null, state,
			null, null, null, null, null, null, null);
	}

	public static Outbound memberLeft(long memberId) {
		return new Outbound("memberLeft", memberId, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null);
	}

	public static Outbound ping(long memberId, int x, int y, int plane, int color, String name) {
		return new Outbound("ping", memberId, null, null, null, null, null, null, null, null,
			x, y, plane, color, name, null, null);
	}

	/** You were removed from the room (kicked, rejected, or the host closed it). */
	public static Outbound kicked() {
		return new Outbound("kicked", null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null);
	}

	public static Outbound error(String detail) {
		return new Outbound("error", null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, detail, null);
	}

	/** Reconnect to the owning node: the client re-dials {@code /n/{nodeId}/api/v2/ws/party} (§3.2/§8). */
	public static Outbound redirect(String nodeId) {
		return new Outbound("redirect", null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, nodeId);
	}

	/** One member in the roster frame. {@code status} is HOST / MEMBER / PENDING. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RosterEntry(long memberId, String name, long accountHash, String status, String role,
		boolean learner, boolean teacher) {
	}
}

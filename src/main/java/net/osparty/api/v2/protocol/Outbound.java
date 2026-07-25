package net.osparty.api.v2.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A server → client Party V2 frame. Null fields are omitted on the wire, so each frame carries only the
 * fields its {@code type} defines. See PARTY_V2_MIGRATION.md §8.
 *
 * <p>The shape is deliberately flat (matching the ad board's frames), which makes it wide: use the static
 * factories, or {@link Builder} for a new frame type, rather than the canonical constructor.
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
	String nodeId,
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

	/** Assigned identity + role on (re)join; followed by a {@code roster} and the current member states. */
	public static Outbound welcome(long memberId, String status) {
		return new Builder("welcome").memberId(memberId).status(status).build();
	}

	/** The server-authoritative roster and room meta. Re-sent on any membership/status/meta change. */
	public static Outbound roster(String host, int capacity, boolean locked, boolean closed,
		String discordUrl, List<RosterEntry> members) {
		return new Builder("roster").host(host).capacity(capacity).locked(locked).closed(closed)
			.discordUrl(discordUrl).members(members).build();
	}

	/** A peer's live snapshot, relayed verbatim ({@code state} is the opaque payload they sent). */
	public static Outbound memberState(long memberId, JsonNode state) {
		return new Builder("memberState").memberId(memberId).state(state).build();
	}

	public static Outbound memberLeft(long memberId) {
		return new Builder("memberLeft").memberId(memberId).build();
	}

	public static Outbound ping(long memberId, int x, int y, int plane, int color, String name) {
		return new Builder("ping").memberId(memberId).x(x).y(y).plane(plane).color(color).name(name).build();
	}

	/** You were removed from the room (kicked, rejected, or the host closed it). */
	public static Outbound kicked() {
		return new Builder("kicked").build();
	}

	public static Outbound error(String detail) {
		return new Builder("error").detail(detail).build();
	}

	/** Reconnect to the owning node: the client re-dials {@code /n/{nodeId}/api/v2/ws/party} (§3.2/§8). */
	public static Outbound redirect(String nodeId) {
		return new Builder("redirect").nodeId(nodeId).build();
	}

	/**
	 * This node is no longer serving the room (graceful drain on shutdown, or ownership lost). The client
	 * drops its node hint and reconnects, landing wherever the gateway sends it; that node then claims the
	 * room or resolves the new owner. Unlike {@code redirect} it names no destination — there isn't one yet.
	 */
	public static Outbound ownerChanged() {
		return new Builder("ownerChanged").build();
	}

	/** Someone started a ready check; peers show the prompt and count {@code memberId} as already ready. */
	public static Outbound readyStart(long memberId, long checkId, String starter) {
		return new Builder("readyStart").memberId(memberId).checkId(checkId).starter(starter).build();
	}

	/** A peer readied up for {@code checkId}. */
	public static Outbound ready(long memberId, long checkId) {
		return new Builder("ready").memberId(memberId).checkId(checkId).build();
	}

	/** A peer landed a defence-draining special attack; receivers merge it into their defence tracker. */
	public static Outbound specDrain(long memberId, int npcIndex, String weapon, int hit, int world) {
		return new Builder("specDrain").memberId(memberId).npcIndex(npcIndex).weapon(weapon).hit(hit)
			.world(world).build();
	}

	/** Host → one member: how to actually join the raid (FC / notice board / obelisk). Targeted delivery. */
	public static Outbound fcRequest(long memberId, String host, String kind, String friendsChat) {
		return new Builder("fcRequest").memberId(memberId).host(host).kind(kind).friendsChat(friendsChat)
			.build();
	}

	/** One step of the host-transfer handshake, delivered only to the member it is aimed at (§16 R8). */
	public static Outbound transferHost(long memberId, String kind, String newHostKey, String newHostName,
		boolean hostStays) {
		return new Builder("transferHost").memberId(memberId).kind(kind).newHostKey(newHostKey)
			.newHostName(newHostName).hostStays(hostStays).build();
	}

	/** One member in the roster frame. {@code status} is HOST / MEMBER / PENDING. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RosterEntry(long memberId, String name, long accountHash, String status, String role,
		boolean learner, boolean teacher) {
	}

	/** Builds a frame by naming only the fields it uses; everything else stays null and is omitted. */
	public static final class Builder {
		private final String type;
		private Long memberId;
		private String status;
		private String host;
		private Integer capacity;
		private Boolean locked;
		private Boolean closed;
		private String discordUrl;
		private List<RosterEntry> members;
		private JsonNode state;
		private Integer x;
		private Integer y;
		private Integer plane;
		private Integer color;
		private String name;
		private String detail;
		private String nodeId;
		private Long checkId;
		private String starter;
		private String kind;
		private String friendsChat;
		private Integer npcIndex;
		private String weapon;
		private Integer hit;
		private Integer world;
		private String newHostKey;
		private String newHostName;
		private Boolean hostStays;

		public Builder(String type) {
			this.type = type;
		}

		public Builder memberId(Long v) {
			this.memberId = v;
			return this;
		}

		public Builder status(String v) {
			this.status = v;
			return this;
		}

		public Builder host(String v) {
			this.host = v;
			return this;
		}

		public Builder capacity(Integer v) {
			this.capacity = v;
			return this;
		}

		public Builder locked(Boolean v) {
			this.locked = v;
			return this;
		}

		public Builder closed(Boolean v) {
			this.closed = v;
			return this;
		}

		public Builder discordUrl(String v) {
			this.discordUrl = v;
			return this;
		}

		public Builder members(List<RosterEntry> v) {
			this.members = v;
			return this;
		}

		public Builder state(JsonNode v) {
			this.state = v;
			return this;
		}

		public Builder x(Integer v) {
			this.x = v;
			return this;
		}

		public Builder y(Integer v) {
			this.y = v;
			return this;
		}

		public Builder plane(Integer v) {
			this.plane = v;
			return this;
		}

		public Builder color(Integer v) {
			this.color = v;
			return this;
		}

		public Builder name(String v) {
			this.name = v;
			return this;
		}

		public Builder detail(String v) {
			this.detail = v;
			return this;
		}

		public Builder nodeId(String v) {
			this.nodeId = v;
			return this;
		}

		public Builder checkId(Long v) {
			this.checkId = v;
			return this;
		}

		public Builder starter(String v) {
			this.starter = v;
			return this;
		}

		public Builder kind(String v) {
			this.kind = v;
			return this;
		}

		public Builder friendsChat(String v) {
			this.friendsChat = v;
			return this;
		}

		public Builder npcIndex(Integer v) {
			this.npcIndex = v;
			return this;
		}

		public Builder weapon(String v) {
			this.weapon = v;
			return this;
		}

		public Builder hit(Integer v) {
			this.hit = v;
			return this;
		}

		public Builder world(Integer v) {
			this.world = v;
			return this;
		}

		public Builder newHostKey(String v) {
			this.newHostKey = v;
			return this;
		}

		public Builder newHostName(String v) {
			this.newHostName = v;
			return this;
		}

		public Builder hostStays(Boolean v) {
			this.hostStays = v;
			return this;
		}

		public Outbound build() {
			return new Outbound(type, memberId, status, host, capacity, locked, closed, discordUrl, members,
				state, x, y, plane, color, name, detail, nodeId, checkId, starter, kind, friendsChat,
				npcIndex, weapon, hit, world, newHostKey, newHostName, hostStays);
		}
	}
}

package net.osparty.api.party.protocol;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import java.util.List;

/**
 * A server → client live-party frame. Null fields are omitted on the wire, so each frame carries only the
 * fields its {@code type} defines. See PARTY_V2_MIGRATION.md §8.
 *
 * <p>The shape is deliberately flat (matching the ad board's frames), which makes it wide: use the static
 * factories, or {@link Builder} for a new frame type, rather than the canonical constructor.
 *
 * <p>Four keys are one character: the ones on the frame that is fanned out to every member of every party
 * on every aggregation window. Shortening the payload inside {@code state} left the wrapper as more than
 * half of that frame's bytes, and {@code memberId}/{@code state} are paid once per update inside it. The
 * type name {@code memberUpdates} goes with them for the same reason. Everything else keeps its name —
 * those frames are sent once per party, where a readable wire is worth more than the bytes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Outbound(
	@JsonProperty("t") String type,
	@JsonProperty("m") Long memberId,
	String status,
	String host,
	Integer capacity,
	Boolean locked,
	Boolean closed,
	String discordUrl,
	List<RosterEntry> members,
	@JsonProperty("s") TokenBuffer state,
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
	Boolean hostStays,
	Long retryAfterMs,
	@JsonProperty("u") List<MemberUpdate> updates,
	JsonNode meta) {

	/** The aggregated fan-out frame's type, short because it is the one every member gets every window. */
	public static final String MEMBER_UPDATES = "mu";

	/**
	 * Assigned identity + role on (re)join; followed by a {@code roster} and the current member states.
	 *
	 * <p>Carries the owning node's id so a client knows where it landed without having to be redirected
	 * there first. Placement can send a new party to a node other than the one its host dialled, so "the
	 * node I connected to" is not reliably "the node that owns my party".
	 */
	public static Outbound welcome(long memberId, String status, String nodeId) {
		return new Builder("welcome").memberId(memberId).status(status).nodeId(nodeId).build();
	}

	/** The server-authoritative roster and room meta. Re-sent on any membership/status/meta change. */
	public static Outbound roster(String host, int capacity, boolean locked, boolean closed,
		String discordUrl, List<RosterEntry> members) {
		return new Builder("roster").host(host).capacity(capacity).locked(locked).closed(closed)
			.discordUrl(discordUrl).members(members).build();
	}

	/**
	 * The host's advertised party settings, relayed verbatim. Distinct from the {@code roster} frame's room
	 * meta: those fields the room itself owns, while this is the backend ad (description, world, loot rule,
	 * requirements, and the host's name) which only the host knows and which members would otherwise never
	 * see change after they joined.
	 */
	public static Outbound meta(JsonNode meta) {
		return new Builder("meta").meta(meta).build();
	}

	/**
	 * Everyone re-send your full live state: someone has just been seated and has no picture of the room.
	 *
	 * <p>This is what replaces the owner node storing each member's last snapshot and replaying it. The
	 * server holds no live state at all, so a joiner's baseline has to come from the members that own it
	 * (PARTY_V2_OPTIMIZATION.md §5.2). Broadcast rather than targeted: routing it to the joiner alone would
	 * mean tracking who is awaiting one, which is exactly the server-held state this design removes.
	 */
	public static Outbound resync() {
		return new Builder("resync").build();
	}

	/**
	 * A peer is still there. Relayed from their {@code heartbeat} so an idle party does not grey itself out:
	 * receivers time a member out after 20s of silence, and once live frames are only sent on change there
	 * is no other traffic to prove liveness (PARTY_V2_OPTIMIZATION.md §4).
	 */
	public static Outbound alive(long memberId) {
		return new Builder("alive").memberId(memberId).build();
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

	/** Reconnect to the owning node: the client re-dials {@code /n/{nodeId}/api/ws} (§3.2/§8). */
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

	/**
	 * The room has no owner <em>yet</em>: its previous owner drained (shutdown or lost lock) and the host
	 * has not re-claimed it on its new node. Distinct from {@code error("no room")}, which is terminal —
	 * this says "come back in {@code retryAfterMs}", so a member that reconnects faster than its host does
	 * not fall out of the party over a handover it merely arrived early for (PARTY_V2_MIGRATION.md §16 R4).
	 *
	 * <p>The window is bounded by the party metadata's TTL: once that lapses the room really is gone and
	 * the next {@code join} gets {@code no room} instead.
	 */
	public static Outbound ownerPending(long retryAfterMs) {
		return new Builder("ownerPending").retryAfterMs(retryAfterMs).build();
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

	/**
	 * Every live update a room collected in one aggregation window, as one frame per recipient.
	 *
	 * <p>Fan-out, not payload, is what a busy party costs the owner node: one member's update becomes a send
	 * to each of its peers, so outbound frames grow with the square of party size. Collecting a window's
	 * worth and giving each member a single frame makes that linear — five sends per tick instead of twenty
	 * on a five-man, and the saving grows with the party.
	 *
	 * <p>Each recipient's copy omits its own updates, so nobody is echoed back to themselves. Updates are
	 * listed in arrival order and never merged: the game tick is longer than the window, so a member almost
	 * never contributes twice, and merging would mean the server reading a payload it has no business
	 * understanding (PARTY_V2_OPTIMIZATION.md §5.2).
	 */
	public static Outbound memberUpdates(List<MemberUpdate> updates) {
		return new Builder(MEMBER_UPDATES).updates(updates).build();
	}

	/** One member's live update inside a {@code memberUpdates} frame. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MemberUpdate(@JsonProperty("m") long memberId, @JsonProperty("s") TokenBuffer state) {
		/**
		 * The same id under the name plugin 1.0.50 reads it by.
		 *
		 * <p>That release annotates {@code m} on the roster row but not on this one, so it reads every update
		 * in the frame as belonging to member 0 — an id nobody on the roster has. The peer each update
		 * describes then never gets it, which is what leaves an applicant with no state for the host to see.
		 *
		 * <p>The one alias that is not free: this rides the frame fanned out to every member of every party on
		 * every window, and it is most of what shortening the wrapper bought (PARTY_V2_OPTIMIZATION.md §5.2).
		 * Delete it with {@link net.osparty.api.web.CapabilitiesController}, and sooner than the rest — it is
		 * the one worth watching {@code osparty.ws.connections} for.
		 */
		@JsonGetter("memberId")
		public long legacyMemberId() {
			return memberId;
		}
	}

	/** One member in the roster frame. {@code status} is HOST / MEMBER / PENDING. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RosterEntry(@JsonProperty("m") long memberId, String name, long accountHash, String status, String role,
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
		private List<MemberUpdate> updates;
		private TokenBuffer state;
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
		private Long retryAfterMs;
		private JsonNode meta;

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

		public Builder updates(List<MemberUpdate> v) {
			this.updates = v;
			return this;
		}

		public Builder members(List<RosterEntry> v) {
			this.members = v;
			return this;
		}

		public Builder state(TokenBuffer v) {
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

		public Builder retryAfterMs(Long v) {
			this.retryAfterMs = v;
			return this;
		}

		public Builder meta(JsonNode v) {
			this.meta = v;
			return this;
		}

		public Outbound build() {
			return new Outbound(type, memberId, status, host, capacity, locked, closed, discordUrl, members,
				state, x, y, plane, color, name, detail, nodeId, checkId, starter, kind, friendsChat,
				npcIndex, weapon, hit, world, newHostKey, newHostName, hostStays, retryAfterMs, updates, meta);
		}
	}
}

package net.osparty.api.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.osparty.api.v2.protocol.Outbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * One party's live state, held entirely in RAM on the owner node. Identity and roster are
 * server-authoritative here (this class assigns status, admits/kicks and enforces capacity); live member
 * snapshots ({@link MemberState#live}) are stored and relayed opaquely. All mutation goes through the
 * per-room {@code lock}, so parties are independent — activity in one room never blocks another
 * (PARTY_V2_MIGRATION.md §11.1).
 *
 * <p>P1 is single-node: the owner is always the node that received the {@code host} frame. Ownership,
 * failover and node-hint routing arrive in P2; ready checks / host transfer / spec drains in P3.
 */
final class LivePartyRoom {
	private static final Logger log = LoggerFactory.getLogger(LivePartyRoom.class);

	final String id;
	final String activityId;
	/** The node this room lives on — a room only ever exists on its owner. Reported on every welcome. */
	final String nodeId;

	private final ObjectMapper mapper;
	private final Object lock = new Object();

	private final Map<Long, MemberState> members = new LinkedHashMap<>();
	private final Map<Long, WebSocketSession> sessions = new LinkedHashMap<>();

	private long hostMemberId;
	private String hostName;
	private int capacity;
	private boolean locked;
	private String discordUrl;
	/** The host's advertised party settings, stored and relayed verbatim (see {@link #setMeta}). */
	private JsonNode meta;

	LivePartyRoom(String id, String activityId, String nodeId, ObjectMapper mapper) {
		this.id = id;
		this.activityId = activityId;
		this.nodeId = nodeId;
		this.mapper = mapper;
	}

	/** Seat the host (the member that created the room). Sends them a welcome + the initial roster. */
	void seatHost(long memberId, WebSocketSession session, String name, long accountHash,
		int capacity, boolean locked, String role, boolean learner, boolean teacher) {
		synchronized (lock) {
			this.hostMemberId = memberId;
			this.hostName = name;
			this.capacity = capacity;
			this.locked = locked;
			MemberState host = new MemberState(memberId, name, accountHash, MemberState.Status.HOST);
			host.role = role;
			host.learner = learner;
			host.teacher = teacher;
			members.put(memberId, host);
			sessions.put(memberId, session);
			send(session, Outbound.welcome(memberId, MemberState.Status.HOST.name(), nodeId));
			sendSnapshotTo(session, memberId);
			broadcastRoster();
		}
	}

	/**
	 * Seat an applicant. Invited joiners are auto-admitted when there's room; everyone else joins PENDING
	 * until the host admits them. Sends them a welcome + snapshot, and re-broadcasts the roster.
	 */
	void seatApplicant(long memberId, WebSocketSession session, String name, long accountHash,
		String role, boolean learner, boolean teacher, boolean invited) {
		synchronized (lock) {
			MemberState.Status status = (invited && canAdmit())
				? MemberState.Status.MEMBER : MemberState.Status.PENDING;
			MemberState member = new MemberState(memberId, name, accountHash, status);
			member.role = role;
			member.learner = learner;
			member.teacher = teacher;
			member.invited = invited;
			members.put(memberId, member);
			sessions.put(memberId, session);
			send(session, Outbound.welcome(memberId, status.name(), nodeId));
			sendSnapshotTo(session, memberId);
			broadcastRoster();
		}
	}

	/**
	 * Refresh a seated member's self-asserted identity. Clients send their name/accountHash in {@code hello}
	 * as soon as those resolve, which for a joiner is only after they are already seated — the live state
	 * payload is opaque here, so this is the only way the roster learns who a member actually is.
	 */
	void identify(long memberId, String name, long accountHash) {
		synchronized (lock) {
			MemberState member = members.get(memberId);
			if (member == null) {
				return;
			}
			boolean changed = false;
			if (name != null && !name.isBlank() && !name.equals(member.name)) {
				member.name = name;
				if (memberId == hostMemberId) {
					hostName = name;
				}
				changed = true;
			}
			if (accountHash != 0 && accountHash != member.accountHash) {
				member.accountHash = accountHash;
				changed = true;
			}
			if (changed) {
				broadcastRoster();
			}
		}
	}

	/** Host admits a PENDING applicant. Server-enforced: only if capacity allows. */
	boolean admit(long actorMemberId, long targetMemberId) {
		synchronized (lock) {
			if (actorMemberId != hostMemberId) {
				return false;
			}
			MemberState target = members.get(targetMemberId);
			if (target == null || target.status != MemberState.Status.PENDING || !canAdmit()) {
				return false;
			}
			target.status = MemberState.Status.MEMBER;
			broadcastRoster();
			return true;
		}
	}

	/** Host kicks/rejects a member. Tells the target it was removed, drops them, re-broadcasts. */
	void remove(long actorMemberId, long targetMemberId) {
		synchronized (lock) {
			if (actorMemberId != hostMemberId || targetMemberId == hostMemberId) {
				return;
			}
			if (members.remove(targetMemberId) == null) {
				return;
			}
			WebSocketSession session = sessions.remove(targetMemberId);
			if (session != null) {
				send(session, Outbound.kicked());
			}
			broadcast(Outbound.memberLeft(targetMemberId), null);
			broadcastRoster();
		}
	}

	/** Store a member's latest live snapshot and relay it to every other session in the room. */
	void updateState(long memberId, JsonNode live) {
		synchronized (lock) {
			MemberState member = members.get(memberId);
			if (member == null) {
				return;
			}
			member.live = live;
			broadcast(Outbound.memberState(memberId, live), memberId);
		}
	}

	void ping(long memberId, int x, int y, int plane, int color, String name) {
		synchronized (lock) {
			if (!members.containsKey(memberId)) {
				return;
			}
			broadcast(Outbound.ping(memberId, x, y, plane, color, name), memberId);
		}
	}

	/** Anyone may start a ready check; peers show the prompt and count the starter as already ready. */
	void readyStart(long memberId, long checkId, String starter) {
		synchronized (lock) {
			if (!isAdmitted(memberId)) {
				return;
			}
			broadcast(Outbound.readyStart(memberId, checkId, starter), memberId);
		}
	}

	void ready(long memberId, long checkId) {
		synchronized (lock) {
			if (!isAdmitted(memberId)) {
				return;
			}
			broadcast(Outbound.ready(memberId, checkId), memberId);
		}
	}

	/** A defence-draining spec landed; peers merge it into their own defence tracker. */
	void specDrain(long memberId, int npcIndex, String weapon, int hit, int world) {
		synchronized (lock) {
			if (!isAdmitted(memberId)) {
				return;
			}
			broadcast(Outbound.specDrain(memberId, npcIndex, weapon, hit, world), memberId);
		}
	}

	/** Host → one member: a join prompt (FC / notice board / obelisk). Delivered only to the target. */
	void fcRequest(long actorMemberId, long targetMemberId, String kind, String friendsChat) {
		synchronized (lock) {
			if (actorMemberId != hostMemberId) {
				return;
			}
			WebSocketSession target = sessions.get(targetMemberId);
			if (target != null) {
				send(target, Outbound.fcRequest(actorMemberId, hostName, kind, friendsChat));
			}
		}
	}

	/**
	 * One step of the host-transfer handshake (PARTY_V2_MIGRATION.md §16 R8), relayed only to the member it
	 * is aimed at. OFFER/COMMIT/ABORT come from the host; ACCEPT comes from the offered member and is
	 * addressed back at the host. On COMMIT the server also moves the authoritative HOST status: the target
	 * becomes host and the old host stays on as a member, or is dropped when {@code hostStays} is false.
	 */
	void transferHost(long actorMemberId, long targetMemberId, String kind, String newHostKey,
		String newHostName, boolean hostStays) {
		synchronized (lock) {
			boolean fromHost = actorMemberId == hostMemberId;
			// Only the host drives the handshake; the sole member-initiated step is ACCEPT, back at the host.
			if (!fromHost && !("ACCEPT".equals(kind) && targetMemberId == hostMemberId)) {
				return;
			}
			MemberState target = members.get(targetMemberId);
			if (target == null) {
				return;
			}
			WebSocketSession session = sessions.get(targetMemberId);
			if (session != null) {
				send(session, Outbound.transferHost(actorMemberId, kind, newHostKey, newHostName, hostStays));
			}
			if (fromHost && "COMMIT".equals(kind)) {
				applyHostHandover(actorMemberId, target, hostStays);
			}
		}
	}

	/** Move HOST status to {@code target}; the old host stays a member or leaves. Call under lock. */
	private void applyHostHandover(long oldHostMemberId, MemberState target, boolean hostStays) {
		MemberState oldHost = members.get(oldHostMemberId);
		target.status = MemberState.Status.HOST;
		hostMemberId = target.memberId;
		hostName = target.name;
		if (oldHost != null) {
			if (hostStays) {
				oldHost.status = MemberState.Status.MEMBER;
			}
			else {
				members.remove(oldHostMemberId);
				sessions.remove(oldHostMemberId);
				broadcast(Outbound.memberLeft(oldHostMemberId), null);
			}
		}
		broadcastRoster();
	}

	void setCapacity(long actorMemberId, int capacity) {
		synchronized (lock) {
			if (actorMemberId == hostMemberId && capacity > 0 && capacity != this.capacity) {
				this.capacity = capacity;
				broadcastRoster();
			}
		}
	}

	void setLocked(long actorMemberId, boolean locked) {
		synchronized (lock) {
			if (actorMemberId == hostMemberId && locked != this.locked) {
				this.locked = locked;
				broadcastRoster();
			}
		}
	}

	/**
	 * Host publishes its advertised party settings (description, world, loot rule, requirements, host name).
	 * Opaque here — the room neither parses nor validates it, exactly as with a member's live state. Members
	 * hold only the snapshot they took when they applied, so without this an edit, or the host name moving in
	 * a transfer, never reaches them.
	 */
	void setMeta(long actorMemberId, JsonNode meta) {
		synchronized (lock) {
			if (actorMemberId != hostMemberId || meta == null || meta.equals(this.meta)) {
				return;
			}
			this.meta = meta;
			broadcast(Outbound.meta(meta), actorMemberId);
		}
	}

	void setDiscordUrl(long actorMemberId, String url) {
		synchronized (lock) {
			if (actorMemberId == hostMemberId && !java.util.Objects.equals(url, discordUrl)) {
				this.discordUrl = url;
				broadcastRoster();
			}
		}
	}

	/**
	 * Drop a member (explicit leave or socket close). Returns true if the host left, in which case the
	 * caller should discard the room: the party has disbanded and remaining members are told it closed.
	 */
	boolean onLeave(long memberId) {
		synchronized (lock) {
			if (members.remove(memberId) == null) {
				sessions.remove(memberId);
				return false;
			}
			sessions.remove(memberId);
			if (memberId == hostMemberId) {
				broadcast(Outbound.roster(hostName, capacity, locked, true, discordUrl, roster()), null);
				return true;
			}
			broadcast(Outbound.memberLeft(memberId), null);
			broadcastRoster();
			return false;
		}
	}

	/**
	 * The outcome of a {@link #pruneClosed} sweep: how many ghosts were dropped, and whether that leaves
	 * the room to be discarded.
	 */
	record Prune(int removed, boolean discard) {
	}

	/**
	 * Drop members whose socket has closed without the container ever telling us.
	 *
	 * <p>Membership is otherwise only ever pruned by {@code afterConnectionClosed}. Miss that callback once
	 * — an abrupt teardown under load, a send that fails after the session is already gone — and the member
	 * is immortal: {@link #sendRaw} quietly skips a closed session rather than removing it, so the room
	 * never empties, is never discarded, and its ownership lock is renewed by the heartbeat for as long as
	 * the node lives. Every such room is a permanent leak of RAM and of a Redis key.
	 *
	 * <p>Only reports {@code discard} when it actually removed someone. A room that is merely empty may be
	 * one that was created microseconds ago and whose host is being seated right now — discarding that
	 * would release an ownership lock out from under a live host.
	 *
	 * @return what the sweep did.
	 */
	Prune pruneClosed() {
		List<Long> gone = new ArrayList<>();
		synchronized (lock) {
			for (Map.Entry<Long, WebSocketSession> entry : sessions.entrySet()) {
				if (!entry.getValue().isOpen()) {
					gone.add(entry.getKey());
				}
			}
		}
		if (gone.isEmpty()) {
			return new Prune(0, false);
		}
		// Outside the collection loop: onLeave takes the lock itself and broadcasts, which can re-enter.
		boolean hostLeft = false;
		for (long memberId : gone) {
			hostLeft |= onLeave(memberId);
		}
		return new Prune(gone.size(), hostLeft || isEmpty());
	}

	boolean isEmpty() {
		synchronized (lock) {
			return members.isEmpty();
		}
	}

	int memberCount() {
		synchronized (lock) {
			return members.size();
		}
	}

	/**
	 * Tell everyone this node has stopped serving the room, so they reconnect and rebuild it on its next
	 * owner. Used for graceful drain at shutdown and when ownership is lost (PARTY_V2_MIGRATION.md §16 R4).
	 */
	void broadcastOwnerChanged() {
		synchronized (lock) {
			broadcast(Outbound.ownerChanged(), null);
		}
	}

	// ---- internals (call under lock) ----------------------------------------

	/** Whether {@code memberId} is seated and past admission (host or member, not a pending applicant). */
	private boolean isAdmitted(long memberId) {
		MemberState member = members.get(memberId);
		return member != null && member.status != MemberState.Status.PENDING;
	}

	/** Whether another applicant can be admitted (host + admitted < capacity, or uncapped). */
	private boolean canAdmit() {
		if (capacity <= 0) {
			return true;
		}
		int admitted = 0;
		for (MemberState m : members.values()) {
			if (m.status != MemberState.Status.PENDING) {
				admitted++;
			}
		}
		return admitted < capacity;
	}

	private List<Outbound.RosterEntry> roster() {
		List<Outbound.RosterEntry> out = new ArrayList<>(members.size());
		for (MemberState m : members.values()) {
			out.add(m.toRosterEntry());
		}
		return out;
	}

	private void broadcastRoster() {
		broadcast(Outbound.roster(hostName, capacity, locked, false, discordUrl, roster()), null);
	}

	/** Give a freshly-seated member the current roster, the host's ad meta and every peer's live snapshot. */
	private void sendSnapshotTo(WebSocketSession session, long selfMemberId) {
		send(session, Outbound.roster(hostName, capacity, locked, false, discordUrl, roster()));
		if (meta != null) {
			send(session, Outbound.meta(meta));
		}
		// Snapshot for the same reason as recipients(): these sends can re-enter onLeave and drop a member.
		for (MemberState m : new ArrayList<>(members.values())) {
			if (m.memberId != selfMemberId && m.live != null) {
				send(session, Outbound.memberState(m.memberId, m.live));
			}
		}
	}

	private void broadcast(Outbound frame, Long exceptMemberId) {
		String json = serialize(frame);
		if (json == null) {
			return;
		}
		TextMessage message = new TextMessage(json);
		for (Map.Entry<Long, WebSocketSession> entry : recipients()) {
			if (exceptMemberId != null && entry.getKey().equals(exceptMemberId)) {
				continue;
			}
			sendRaw(entry.getValue(), message);
		}
	}

	/**
	 * A snapshot of the sessions to send to, rather than the live map.
	 *
	 * <p>A send can close its own session — a broken pipe, or a client that has already gone — and Tomcat
	 * runs that close <em>inline on the sending thread</em>. That re-enters {@link #onLeave} and removes from
	 * {@code sessions} midway through the fan-out. The room lock is no defence: it is the same thread, so it
	 * simply re-enters. Iterating the map directly then dies with a {@code ConcurrentModificationException}
	 * that propagates out of the frame handler and takes down the <em>sender's</em> session too — one dead
	 * peer knocking out a healthy one, and under load that cascades.
	 */
	private List<Map.Entry<Long, WebSocketSession>> recipients() {
		List<Map.Entry<Long, WebSocketSession>> out = new ArrayList<>(sessions.size());
		sessions.forEach((memberId, session) -> out.add(Map.entry(memberId, session)));
		return out;
	}

	private void send(WebSocketSession session, Outbound frame) {
		String json = serialize(frame);
		if (json != null) {
			sendRaw(session, new TextMessage(json));
		}
	}

	private void sendRaw(WebSocketSession session, TextMessage message) {
		if (!session.isOpen()) {
			return;
		}
		try {
			session.sendMessage(message);
		}
		catch (Exception e) {
			log.debug("Party V2 room {}: dropping send to {}: {}", id, session.getId(), e.toString());
		}
	}

	private String serialize(Outbound frame) {
		try {
			return mapper.writeValueAsString(frame);
		}
		catch (Exception e) {
			log.warn("Party V2 room {}: failed to serialise {} frame", id, frame.type(), e);
			return null;
		}
	}
}

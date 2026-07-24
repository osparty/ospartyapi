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

	private final ObjectMapper mapper;
	private final Object lock = new Object();

	private final Map<Long, MemberState> members = new LinkedHashMap<>();
	private final Map<Long, WebSocketSession> sessions = new LinkedHashMap<>();

	private long hostMemberId;
	private String hostName;
	private int capacity;
	private boolean locked;
	private String discordUrl;

	LivePartyRoom(String id, String activityId, ObjectMapper mapper) {
		this.id = id;
		this.activityId = activityId;
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
			send(session, Outbound.welcome(memberId, MemberState.Status.HOST.name()));
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
			send(session, Outbound.welcome(memberId, status.name()));
			sendSnapshotTo(session, memberId);
			broadcastRoster();
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

	boolean isEmpty() {
		synchronized (lock) {
			return members.isEmpty();
		}
	}

	// ---- internals (call under lock) ----------------------------------------

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

	/** Give a freshly-seated member the current roster and every peer's last live snapshot. */
	private void sendSnapshotTo(WebSocketSession session, long selfMemberId) {
		send(session, Outbound.roster(hostName, capacity, locked, false, discordUrl, roster()));
		for (MemberState m : members.values()) {
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
		for (Map.Entry<Long, WebSocketSession> entry : sessions.entrySet()) {
			if (exceptMemberId != null && entry.getKey().equals(exceptMemberId)) {
				continue;
			}
			sendRaw(entry.getValue(), message);
		}
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

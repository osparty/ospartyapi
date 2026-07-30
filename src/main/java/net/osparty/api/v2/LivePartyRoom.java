package net.osparty.api.v2;

import net.osparty.api.transport.PartySession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.osparty.api.v2.protocol.Outbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private final Map<Long, PartySession> sessions = new LinkedHashMap<>();
	/**
	 * When each member was last heard from. Liveness cannot be read off the socket: a half-open connection
	 * — client gone, proxy never tearing the backend leg down — leaves {@link PartySession#isOpen()}
	 * true indefinitely, so a room of the departed never empties and is never discarded. Traffic is the only
	 * honest signal, and a live party always has some: a member with nothing to report still heartbeats every
	 * few seconds, which is exactly what that frame is for. (It used to be the plugin's periodic full resync
	 * — that is gone, and the heartbeat replaced it precisely so this sweep kept working.)
	 *
	 * <p>Concurrent and outside the room lock on purpose — {@link #touch} runs on every inbound frame, which
	 * is the hottest path in the system.
	 */
	private final Map<Long, Long> lastSeen = new java.util.concurrent.ConcurrentHashMap<>();

	/** Shortest gap between resync rounds; see {@link #broadcastResync}. */
	private static final long RESYNC_MIN_INTERVAL_MS = 3_000;

	/** The two ends of a {@code memberUpdates} frame, between which pre-encoded updates are pasted. */
	private static final byte[] UPDATES_PREFIX =
		("{\"t\":\"" + Outbound.MEMBER_UPDATES + "\",\"u\":[")
			.getBytes(java.nio.charset.StandardCharsets.UTF_8);
	private static final byte[] UPDATES_SUFFIX =
		"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

	/** This window's updates, in arrival order. Drained by {@link #flush}. */
	private List<Outbound.MemberUpdate> pending = new ArrayList<>();
	/** Whether anything in {@link #pending} was sent as urgent, which overrides the idle window. */
	private boolean pendingUrgent;
	private long lastFlushAt;
	private long lastResyncAt;
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
	void seatHost(long memberId, PartySession session, String name, long accountHash,
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
			lastSeen.put(memberId, System.currentTimeMillis());
			send(session, Outbound.welcome(memberId, MemberState.Status.HOST.name(), nodeId));
			sendSnapshotTo(session);
			broadcastRoster();
			broadcastResync(memberId);
		}
	}

	/**
	 * Seat an applicant. Invited joiners are auto-admitted when there's room; everyone else joins PENDING
	 * until the host admits them. Sends them a welcome + snapshot, and re-broadcasts the roster.
	 */
	void seatApplicant(long memberId, PartySession session, String name, long accountHash,
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
			lastSeen.put(memberId, System.currentTimeMillis());
			send(session, Outbound.welcome(memberId, status.name(), nodeId));
			sendSnapshotTo(session);
			broadcastRoster();
			broadcastResync(memberId);
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
			PartySession session = sessions.remove(targetMemberId);
			lastSeen.remove(targetMemberId);
			if (session != null) {
				send(session, Outbound.kicked());
			}
			broadcast(Outbound.memberLeft(targetMemberId), null);
			broadcastRoster();
		}
	}

	/**
	 * Queue a member's live update for the next flush, and forget it afterwards.
	 *
	 * <p>Nothing is stored: frames carry only what changed, so the last one is a fragment rather than a
	 * picture, and an owner that kept it would hand that fragment to the next joiner as though it were
	 * complete. Joiners are served by {@link #broadcastResync} instead (PARTY_V2_OPTIMIZATION.md §5.2).
	 *
	 * <p>Not sent immediately: one update owes a send to every peer, so a busy room's outbound frames grow
	 * with the square of its size. Holding a short window and giving each member one frame with everything
	 * that happened in it makes that linear. The window is shorter than a game tick, so this costs a fraction
	 * of a tick of latency and a member almost never queues twice within one.
	 *
	 * <p>{@code urgent} is the sender's own judgement that this one should not wait out the idle window: a
	 * vital that moved <em>down</em>, which is damage taken, prayer drained or a spec spent. See {@link #flush}.
	 */
	void updateState(long memberId, TokenBuffer live, boolean urgent) {
		synchronized (lock) {
			if (!members.containsKey(memberId) || live == null) {
				return;
			}
			pending.add(new Outbound.MemberUpdate(memberId, live));
			pendingUrgent |= urgent;
		}
	}

	/** Relay a member's keep-alive to its peers, so an idle party does not time itself out (§4). */
	void alive(long memberId) {
		synchronized (lock) {
			if (!members.containsKey(memberId)) {
				return;
			}
			broadcast(Outbound.alive(memberId), memberId);
		}
	}

	/**
	 * Send this window's updates: one frame per member, carrying everyone else's.
	 *
	 * <p>Each recipient's list omits its own updates. Sharing one frame with the whole room instead would
	 * serialise once rather than once per member — that was tried, and measured: it delivered the same
	 * information in 12% more frames and 13% more bytes (a member whose update was alone in a window used to
	 * receive nothing, and then received a frame containing only itself) for no CPU saving anyone could
	 * detect. Serialisation is not what this costs; the send is.
	 *
	 * <p>Two speeds, because sends are what this costs and most of a party's wall-clock time is not combat.
	 * An urgent update — a vital that moved down — goes out on this call. Everything else waits until
	 * {@code idleMs} has passed since the room last flushed, so a party standing in a bank pays one round a
	 * second instead of one every window. The gap is measured from the last flush rather than from the oldest
	 * pending update, so the first update after a quiet spell is still immediate; only sustained non-urgent
	 * chatter is throttled.
	 */
	void flush(long idleMs) {
		List<Outbound.MemberUpdate> window;
		synchronized (lock) {
			if (pending.isEmpty()) {
				return;
			}
			long now = System.currentTimeMillis();
			if (!pendingUrgent && now - lastFlushAt < idleMs) {
				return;
			}
			lastFlushAt = now;
			pendingUrgent = false;
			// Swap rather than copy: the outgoing list is handed to Jackson, so it cannot be the one the
			// next window appends to.
			window = pending;
			pending = new ArrayList<>();
		}
		// Serialise each update once for the whole window, not once per recipient: in a five-man that is five
		// encodes instead of twenty, and the bytes are identical either way — only which of them a given
		// member gets differs. Sharing one whole frame was tried instead and measured worse (see above); this
		// keeps the tailored frame and drops only the repeated work behind it.
		byte[][] encoded = new byte[window.size()][];
		for (int i = 0; i < window.size(); i++) {
			encoded[i] = serializeBytes(window.get(i));
		}
		synchronized (lock) {
			for (Map.Entry<Long, PartySession> entry : recipients()) {
				byte[] frame = updatesFrame(window, encoded, entry.getKey());
				if (frame != null) {
					sendRaw(entry.getValue(), frame);
				}
			}
		}
	}

	/**
	 * Build one recipient's {@code memberUpdates} frame by pasting the pre-encoded updates together, leaving
	 * out its own. Null when nothing is left for them.
	 *
	 * <p>Hand-assembled rather than handed back to Jackson because the parts are already bytes; the result is
	 * byte-for-byte what serialising {@link Outbound#memberUpdates} would produce, which is why the fields
	 * are in the order the record declares them.
	 */
	private byte[] updatesFrame(List<Outbound.MemberUpdate> window, byte[][] encoded, long recipient) {
		int length = UPDATES_PREFIX.length + UPDATES_SUFFIX.length;
		int count = 0;
		for (int i = 0; i < encoded.length; i++) {
			if (encoded[i] == null || window.get(i).memberId() == recipient) {
				continue;
			}
			length += encoded[i].length + (count == 0 ? 0 : 1);
			count++;
		}
		if (count == 0) {
			return null;
		}
		byte[] frame = new byte[length];
		int at = 0;
		System.arraycopy(UPDATES_PREFIX, 0, frame, at, UPDATES_PREFIX.length);
		at += UPDATES_PREFIX.length;
		boolean first = true;
		for (int i = 0; i < encoded.length; i++) {
			if (encoded[i] == null || window.get(i).memberId() == recipient) {
				continue;
			}
			if (!first) {
				frame[at++] = ',';
			}
			first = false;
			System.arraycopy(encoded[i], 0, frame, at, encoded[i].length);
			at += encoded[i].length;
		}
		System.arraycopy(UPDATES_SUFFIX, 0, frame, at, UPDATES_SUFFIX.length);
		return frame;
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
			PartySession target = sessions.get(targetMemberId);
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
			PartySession session = sessions.get(targetMemberId);
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
				lastSeen.remove(memberId);
				return false;
			}
			sessions.remove(memberId);
			lastSeen.remove(memberId);
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
	Prune pruneClosed(long silentAfterMs) {
		List<Long> gone = new ArrayList<>();
		synchronized (lock) {
			long now = System.currentTimeMillis();
			for (Map.Entry<Long, PartySession> entry : sessions.entrySet()) {
				Long seen = lastSeen.get(entry.getKey());
				boolean silent = seen != null && now - seen > silentAfterMs;
				if (!entry.getValue().isOpen() || silent) {
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

	/**
	 * Note that we just heard from a member. Deliberately outside the room lock: this runs on every inbound
	 * frame, and a live party's state traffic is the hottest path there is.
	 */
	void touch(long memberId) {
		lastSeen.replace(memberId, System.currentTimeMillis());
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

	/**
	 * Give a freshly-seated member the room's own state: the roster and the host's ad meta, both of which
	 * this node owns. Peers' live state is not here — the owner does not hold any — and arrives instead from
	 * the {@link #broadcastResync} the peers answer on their next tick.
	 */
	private void sendSnapshotTo(PartySession session) {
		send(session, Outbound.roster(hostName, capacity, locked, false, discordUrl, roster()));
		if (meta != null) {
			send(session, Outbound.meta(meta));
		}
	}

	/**
	 * Ask the seated members to re-send their full live state for the benefit of {@code joinerMemberId}.
	 *
	 * <p>Rate-limited per room: several members seated in the same moment (a party re-forming, or a wave of
	 * reconnects) would otherwise each trigger a full round from everyone. One round covers all of them,
	 * since it is a broadcast and the answers reach every session.
	 *
	 * <p>Skipped for the first member — there is nobody to answer — and the joiner is excluded because it
	 * pushes its own state unprompted on its next tick anyway.
	 */
	private void broadcastResync(long joinerMemberId) {
		if (members.size() < 2) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastResyncAt < RESYNC_MIN_INTERVAL_MS) {
			return;
		}
		lastResyncAt = now;
		broadcast(Outbound.resync(), joinerMemberId);
	}

	private void broadcast(Outbound frame, Long exceptMemberId) {
		byte[] json = serialize(frame);
		if (json == null) {
			return;
		}
		if (exceptMemberId == null) {
			// The common case: no key comparison, no entry objects.
			for (PartySession session : openSessions()) {
				sendRaw(session, json);
			}
			return;
		}
		for (Map.Entry<Long, PartySession> entry : recipients()) {
			if (entry.getKey().equals(exceptMemberId)) {
				continue;
			}
			sendRaw(entry.getValue(), json);
		}
	}

	/** As {@link #recipients()}, for the sends that go to everyone — same snapshot rule, no entry boxing. */
	private List<PartySession> openSessions() {
		return new ArrayList<>(sessions.values());
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
	private List<Map.Entry<Long, PartySession>> recipients() {
		List<Map.Entry<Long, PartySession>> out = new ArrayList<>(sessions.size());
		sessions.forEach((memberId, session) -> out.add(Map.entry(memberId, session)));
		return out;
	}

	private void send(PartySession session, Outbound frame) {
		byte[] json = serialize(frame);
		if (json != null) {
			sendRaw(session, json);
		}
	}

	private void sendRaw(PartySession session, byte[] json) {
		if (!session.isOpen()) {
			return;
		}
		try {
			session.send(json);
		}
		catch (Exception e) {
			log.debug("Party V2 room {}: dropping send to {}: {}", id, session.id(), e.toString());
		}
	}

	private byte[] serialize(Outbound frame) {
		try {
			return mapper.writeValueAsBytes(frame);
		}
		catch (Exception e) {
			log.warn("Party V2 room {}: failed to serialise {} frame", id, frame.type(), e);
			return null;
		}
	}

	/** As {@link #serialize}, for one update inside an aggregated frame. Null if it cannot be written. */
	private byte[] serializeBytes(Outbound.MemberUpdate update) {
		try {
			return mapper.writeValueAsBytes(update);
		}
		catch (Exception e) {
			log.warn("Party V2 room {}: failed to serialise an update from {}", id, update.memberId(), e);
			return null;
		}
	}
}

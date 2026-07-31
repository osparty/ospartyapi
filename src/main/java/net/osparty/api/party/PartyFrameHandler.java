package net.osparty.api.party;

import net.osparty.api.transport.SocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.osparty.api.party.protocol.Inbound;
import net.osparty.api.party.protocol.Outbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The live-party protocol, with no transport in it. Decodes {@link Inbound} frames and drives the in-memory
 * {@link PartyManager}; the roster is server-authoritative (PARTY_V2_MIGRATION.md §3.1/§8).
 *
 * <p>Everything a connection means to this service lives here, behind {@link SocketSession}: which room it is
 * in, which member id it was assigned, what it claims to be called. What actually carries the bytes knows
 * nothing about any of it beyond calling {@link #onOpen}, {@link #onMessage} and {@link #onClose}.
 */
@Component
public class PartyFrameHandler {
	private static final Logger log = LoggerFactory.getLogger(PartyFrameHandler.class);

	/**
	 * How long a joiner waits before retrying a room that is mid-handover. Short, because the gap it covers
	 * is one host reconnect plus one claim; the client bounds the total number of retries.
	 */
	private static final long HANDOVER_RETRY_MS = 1_000;

	private final PartyManager manager;
	private final ObjectMapper mapper;
	private final Map<String, Ctx> contexts = new ConcurrentHashMap<>();

	public PartyFrameHandler(PartyManager manager, ObjectMapper mapper) {
		this.manager = manager;
		this.mapper = mapper;
	}

	public void onOpen(SocketSession session) {
		contexts.put(session.id(), new Ctx(session));
		log.info("Party WS connected: session={}", session.id());
	}

	public void onClose(String sessionId, String reason) {
		Ctx ctx = contexts.remove(sessionId);
		if (ctx != null) {
			handleLeave(ctx);
		}
		log.info("Party WS closed: session={} reason={}", sessionId, reason);
	}

	/**
	 * One inbound frame, as the UTF-8 bytes the client sent. Bytes rather than a decoded string because
	 * Jackson reads them directly — decoding to a String first would add a pass over every frame for nothing.
	 */
	public void onMessage(String sessionId, byte[] payload) {
		Ctx ctx = contexts.get(sessionId);
		if (ctx == null) {
			return;
		}
		Inbound in;
		try {
			in = mapper.readValue(payload, Inbound.class);
		}
		catch (Exception e) {
			return;
		}
		if (in.type() == null) {
			return;
		}
		if (ctx.roomId != null) {
			// Any frame at all counts as proof of life; the sweep has nothing else to go on.
			manager.touch(ctx.roomId, ctx.memberId);
		}
		switch (in.type()) {
			case "hello":
				handleHello(ctx, in);
				break;
			case "host":
				handleHost(ctx, in);
				break;
			case "join":
				handleJoin(ctx, in);
				break;
			// A live update carrying whichever parts changed.
			case "update":
				withRoom(ctx, room -> room.queueUpdate(ctx.memberId, in.state(), urgent(in)));
				break;
			case "heartbeat":
				// The touch() above already fed the ghost sweep; this only tells the peers, whose own
				// timeout has nothing else to go on once live frames are sent purely on change.
				withRoom(ctx, room -> room.alive(ctx.memberId));
				break;
			case "ping":
				handlePing(ctx, in);
				break;
			case "command":
				handleCommand(ctx, in);
				break;
			case "setCapacity":
				withOwnedRoom(ctx, room -> {
					if (in.capacity() != null) {
						room.setCapacity(ctx.memberId, in.capacity());
					}
				});
				break;
			case "setLocked":
				withOwnedRoom(ctx, room -> {
					if (in.locked() != null) {
						room.setLocked(ctx.memberId, in.locked());
					}
				});
				break;
			case "setDiscord":
				withOwnedRoom(ctx, room -> room.setDiscordUrl(ctx.memberId, in.url()));
				break;
			case "setMeta":
				withOwnedRoom(ctx, room -> room.setMeta(ctx.memberId, in.meta()));
				break;
			case "readyStart":
				withRoom(ctx, room -> {
					if (in.checkId() != null) {
						room.readyStart(ctx.memberId, in.checkId(), in.starter());
					}
				});
				break;
			case "ready":
				withRoom(ctx, room -> {
					if (in.checkId() != null) {
						room.ready(ctx.memberId, in.checkId());
					}
				});
				break;
			case "specDrain":
				withRoom(ctx, room -> room.specDrain(ctx.memberId,
					in.npcIndex() == null ? -1 : in.npcIndex(), in.weapon(),
					in.hit() == null ? 0 : in.hit(), in.world() == null ? 0 : in.world()));
				break;
			case "fcRequest":
				withRoom(ctx, room -> {
					if (in.target() != null) {
						room.fcRequest(ctx.memberId, in.target(), in.kind(), in.friendsChat());
					}
				});
				break;
			case "transferHost":
				withOwnedRoom(ctx, room -> {
					if (in.target() != null && in.kind() != null) {
						room.transferHost(ctx.memberId, in.target(), in.kind(), in.newHostKey(),
							in.newHostName(), Boolean.TRUE.equals(in.hostStays()));
					}
				});
				break;
			case "leave":
				handleLeave(ctx);
				break;
			default:
				break;
		}
	}

	/**
	 * Whether the sender asked for this update to skip the idle window. Absent means no — a client from
	 * before the flag simply never gets the fast path, which costs it latency and nothing else.
	 */
	private static boolean urgent(Inbound in) {
		return Boolean.TRUE.equals(in.urgent());
	}

	private void handleHello(Ctx ctx, Inbound in) {
		ensureMemberId(ctx);
		if (in.accountHash() != null) {
			ctx.accountHash = in.accountHash();
		}
		if (in.name() != null) {
			ctx.name = in.name();
		}
		// A joiner only knows its own name once it is in-game, which is usually after it was seated, so a
		// later hello has to reach the roster too (see PartyRoom.identify).
		withRoom(ctx, room -> room.identify(ctx.memberId, ctx.name, ctx.accountHash));
	}

	private void handleHost(Ctx ctx, Inbound in) {
		if (in.room() == null || in.room().isBlank()) {
			send(ctx, Outbound.error("missing room"));
			return;
		}
		leaveCurrentRoom(ctx, in.room());
		// Placement, before the claim: a new room goes where there is capacity, not where its host's socket
		// happens to have landed. Redirecting reuses the join path the client already implements — it adopts
		// the hint, reconnects, and re-sends this same frame from onOpen.
		java.util.Optional<String> lighter = ctx.session.nodeHinted()
			? java.util.Optional.empty()
			: manager.placementFor(in.room());
		if (lighter.isPresent()) {
			manager.recordRebalance();
			log.info("Party host rebalance: session={} room={} -> {}",
				ctx.session.id(), in.room(), lighter.get());
			send(ctx, Outbound.redirect(lighter.get()));
			return;
		}
		PartyRoom room = manager.hostRoom(in.room(), in.activityId());
		if (room == null) {
			// Another node already owns this room; send the host to that owner.
			manager.owner(in.room()).ifPresentOrElse(
				owner -> {
					manager.recordRedirect();
					log.info("Party host redirect: session={} room={} -> {}",
						ctx.session.id(), in.room(), owner.nodeId());
					send(ctx, Outbound.redirect(owner.nodeId()));
				},
				() -> send(ctx, Outbound.error("host claim failed")));
			return;
		}
		ensureMemberId(ctx);
		String name = in.hostName() != null ? in.hostName() : ctx.name;
		long accountHash = in.accountHash() != null ? in.accountHash() : ctx.accountHash;
		ctx.roomId = in.room();
		room.seatHost(ctx.memberId, ctx.session, name, accountHash,
			in.capacity() == null ? 0 : in.capacity(),
			Boolean.TRUE.equals(in.locked()), in.role(),
			Boolean.TRUE.equals(in.learner()), Boolean.TRUE.equals(in.teacher()));
		log.info("Party host: session={} room={} member={}", ctx.session.id(), in.room(), ctx.memberId);
	}

	private void handleJoin(Ctx ctx, Inbound in) {
		if (in.room() == null || in.room().isBlank()) {
			send(ctx, Outbound.error("missing room"));
			return;
		}
		leaveCurrentRoom(ctx, in.room());
		PartyRoom room = manager.room(in.room());
		if (room == null) {
			// Not hosted here: another node may own it, it may be mid-handover, or it may simply not exist.
			manager.owner(in.room()).ifPresentOrElse(
				owner -> {
					if (manager.nodeId().equals(owner.nodeId())) {
						// We hold the lock but not the room: this node reclaimed it from a dead owner and is
						// waiting for the host to rebuild it. Redirecting here would point the client back at
						// the node it is already talking to, which it would rightly ignore — so defer.
						deferUntilHostReturns(ctx, in.room());
						return;
					}
					manager.recordRedirect();
					log.info("Party redirect: session={} room={} -> {}",
						ctx.session.id(), in.room(), owner.nodeId());
					send(ctx, Outbound.redirect(owner.nodeId()));
				},
				() -> sendUnowned(ctx, in.room(), Boolean.TRUE.equals(in.invited())));
			return;
		}
		ensureMemberId(ctx);
		ctx.roomId = in.room();
		room.seatApplicant(ctx.memberId, ctx.session,
			in.name() != null ? in.name() : ctx.name,
			in.accountHash() != null ? in.accountHash() : ctx.accountHash,
			in.role(), Boolean.TRUE.equals(in.learner()), Boolean.TRUE.equals(in.teacher()),
			Boolean.TRUE.equals(in.invited()));
		log.info("Party join: session={} room={} member={}", ctx.session.id(), in.room(), ctx.memberId);
	}

	/**
	 * Answer a join for a room nobody owns. A room whose owner drained moments ago is coming back — the
	 * host is reconnecting and re-claiming it right now — so the joiner is told to retry rather than that
	 * the party is gone. Members reconnect at the same instant as their host but have less to do before
	 * they arrive, so without this they lose the race by default and drop out of the party (§16 R4).
	 *
	 * <p>An invited joiner gets the same answer even for a room that has never existed, because someone
	 * told it to come: the host is on its way and has more to do than the guest before it arrives. That
	 * race is ordinary — it widens whenever the host is placed on another node and has to reconnect there —
	 * and losing it should cost a second, not the party. A joiner arriving on a code nobody gave it still
	 * gets the error straight away, which is the right answer to a typo. The client bounds the retries
	 * either way, so a host that never comes ends the same way it used to, just later.
	 */
	private void sendUnowned(Ctx ctx, String room, boolean invited) {
		if (manager.handoverPending(room) || invited) {
			deferUntilHostReturns(ctx, room);
			return;
		}
		log.info("Party join refused: session={} room={} — no such room", ctx.session.id(), room);
		send(ctx, Outbound.error("no room"));
	}

	/** Tell a joiner the room is coming back and to try again shortly. */
	private void deferUntilHostReturns(Ctx ctx, String room) {
		manager.recordOwnerPending();
		log.info("Party join deferred: session={} room={} — awaiting host, retry in {}ms",
			ctx.session.id(), room, HANDOVER_RETRY_MS);
		send(ctx, Outbound.ownerPending(HANDOVER_RETRY_MS));
	}

	private void handlePing(Ctx ctx, Inbound in) {
		if (in.x() == null || in.y() == null) {
			return;
		}
		withRoom(ctx, room -> room.ping(ctx.memberId, in.x(), in.y(),
			in.plane() == null ? 0 : in.plane(), in.color() == null ? 0 : in.color(), in.name()));
	}

	private void handleCommand(Ctx ctx, Inbound in) {
		if (in.action() == null || in.target() == null) {
			return;
		}
		withOwnedRoom(ctx, room -> {
			if ("ADMIT".equals(in.action())) {
				room.admit(ctx.memberId, in.target());
			}
			else if ("KICK".equals(in.action()) || "REJECT".equals(in.action())) {
				room.remove(ctx.memberId, in.target());
			}
		});
	}

	/** Leave the current room before hosting/joining a different one, so no ghost membership is left behind. */
	private void leaveCurrentRoom(Ctx ctx, String newRoom) {
		if (ctx.roomId != null && !ctx.roomId.equals(newRoom)) {
			handleLeave(ctx);
		}
	}

	private void handleLeave(Ctx ctx) {
		String roomId = ctx.roomId;
		if (roomId == null) {
			return;
		}
		ctx.roomId = null;
		PartyRoom room = manager.room(roomId);
		if (room == null) {
			return;
		}
		boolean hostLeft = room.onLeave(ctx.memberId);
		if (hostLeft || room.isEmpty()) {
			manager.discard(roomId);
		}
	}

	private void withRoom(Ctx ctx, java.util.function.Consumer<PartyRoom> action) {
		if (ctx.roomId == null) {
			return;
		}
		PartyRoom room = manager.room(ctx.roomId);
		if (room != null) {
			action.accept(room);
		}
	}

	/**
	 * As {@link #withRoom}, but fenced on still owning the room (PARTY_V2_MIGRATION.md §16 R5): used for
	 * authoritative actions (admission, kick, room settings, host transfer) so a node whose lock expired
	 * cannot keep mutating a room another node now owns. A lost room is drained instead, sending its
	 * members to reconnect. Not used on the live-state hot path, which must never touch Redis.
	 */
	private void withOwnedRoom(Ctx ctx, java.util.function.Consumer<PartyRoom> action) {
		if (ctx.roomId == null) {
			return;
		}
		PartyRoom room = manager.room(ctx.roomId);
		if (room == null) {
			return;
		}
		if (!manager.ownsRoom(ctx.roomId)) {
			log.info("Party: refusing authoritative action on {} — no longer owned here", ctx.roomId);
			manager.recordFailover();
			manager.drain(ctx.roomId, false);
			return;
		}
		action.accept(room);
	}

	private void ensureMemberId(Ctx ctx) {
		if (ctx.memberId == 0) {
			ctx.memberId = manager.nextMemberId();
		}
	}

	private void send(Ctx ctx, Outbound frame) {
		if (!ctx.session.isOpen()) {
			return;
		}
		try {
			ctx.session.send(mapper.writeValueAsBytes(frame));
		}
		catch (Exception e) {
			log.debug("Party: send to {} failed: {}", ctx.session.id(), e.toString());
		}
	}

	/** Per-connection state: the session, its assigned member id, identity and current room. */
	private static final class Ctx {
		final SocketSession session;
		volatile long memberId;
		volatile long accountHash;
		volatile String name;
		volatile String roomId;

		Ctx(SocketSession session) {
			this.session = session;
		}
	}
}

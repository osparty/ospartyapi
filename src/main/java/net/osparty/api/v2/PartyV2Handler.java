package net.osparty.api.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.osparty.api.v2.protocol.Inbound;
import net.osparty.api.v2.protocol.Outbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Party V2 live-party WebSocket handler. Decodes {@link Inbound} frames and drives the in-memory
 * {@link PartyV2Manager}; the roster is server-authoritative (PARTY_V2_MIGRATION.md §3.1/§8).
 *
 * <p>Gated OFF by default ({@code app.party-v2.enabled}); loads only alongside
 * {@link PartyV2WebSocketConfig}, so V1 is unaffected.
 *
 * <p>P1 is single-node — the owner is always this node. P2 adds ownership + node-hint routing (a joiner
 * for a room hosted elsewhere is answered with a {@code redirect}); P3 adds ready checks / host transfer /
 * spec drains / friends-chat prompts.
 */
@Component
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2Handler extends TextWebSocketHandler {
	private static final Logger log = LoggerFactory.getLogger(PartyV2Handler.class);

	private static final int SEND_TIME_LIMIT_MS = 10_000;
	private static final int SEND_BUFFER_LIMIT = 512 * 1024;

	private final PartyV2Manager manager;
	private final ObjectMapper mapper;
	private final Map<String, Ctx> contexts = new ConcurrentHashMap<>();

	public PartyV2Handler(PartyV2Manager manager, ObjectMapper mapper) {
		this.manager = manager;
		this.mapper = mapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(
			session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
		contexts.put(session.getId(), new Ctx(guarded));
		log.info("Party V2 WS connected: session={}", session.getId());
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		Ctx ctx = contexts.get(session.getId());
		if (ctx == null) {
			return;
		}
		Inbound in;
		try {
			in = mapper.readValue(message.getPayload(), Inbound.class);
		}
		catch (Exception e) {
			return;
		}
		if (in.type() == null) {
			return;
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
			case "state":
				withRoom(ctx, room -> room.updateState(ctx.memberId, in.state()));
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

	private void handleHello(Ctx ctx, Inbound in) {
		ensureMemberId(ctx);
		if (in.accountHash() != null) {
			ctx.accountHash = in.accountHash();
		}
		if (in.name() != null) {
			ctx.name = in.name();
		}
		// A joiner only knows its own name once it is in-game, which is usually after it was seated, so a
		// later hello has to reach the roster too (see LivePartyRoom.identify).
		withRoom(ctx, room -> room.identify(ctx.memberId, ctx.name, ctx.accountHash));
	}

	private void handleHost(Ctx ctx, Inbound in) {
		if (in.room() == null || in.room().isBlank()) {
			send(ctx, Outbound.error("missing room"));
			return;
		}
		leaveCurrentRoom(ctx, in.room());
		LivePartyRoom room = manager.hostRoom(in.room(), in.activityId());
		if (room == null) {
			// Another node already owns this room; send the host to that owner.
			manager.owner(in.room()).ifPresentOrElse(
				owner -> {
					manager.recordRedirect();
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
		log.info("Party V2 host: session={} room={} member={}", ctx.session.getId(), in.room(), ctx.memberId);
	}

	private void handleJoin(Ctx ctx, Inbound in) {
		if (in.room() == null || in.room().isBlank()) {
			send(ctx, Outbound.error("missing room"));
			return;
		}
		leaveCurrentRoom(ctx, in.room());
		LivePartyRoom room = manager.room(in.room());
		if (room == null) {
			// Not hosted here: if another node owns it, redirect there; otherwise no such party exists.
			manager.owner(in.room()).ifPresentOrElse(
				owner -> {
					manager.recordRedirect();
					send(ctx, Outbound.redirect(owner.nodeId()));
				},
				() -> send(ctx, Outbound.error("no room")));
			return;
		}
		ensureMemberId(ctx);
		ctx.roomId = in.room();
		room.seatApplicant(ctx.memberId, ctx.session,
			in.name() != null ? in.name() : ctx.name,
			in.accountHash() != null ? in.accountHash() : ctx.accountHash,
			in.role(), Boolean.TRUE.equals(in.learner()), Boolean.TRUE.equals(in.teacher()),
			Boolean.TRUE.equals(in.invited()));
		log.info("Party V2 join: session={} room={} member={}", ctx.session.getId(), in.room(), ctx.memberId);
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
		LivePartyRoom room = manager.room(roomId);
		if (room == null) {
			return;
		}
		boolean hostLeft = room.onLeave(ctx.memberId);
		if (hostLeft || room.isEmpty()) {
			manager.discard(roomId);
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		Ctx ctx = contexts.remove(session.getId());
		if (ctx != null) {
			handleLeave(ctx);
		}
		log.info("Party V2 WS closed: session={} status={}", session.getId(), status);
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		try {
			session.close(CloseStatus.SERVER_ERROR);
		}
		catch (Exception ignored) {
		}
	}

	private void withRoom(Ctx ctx, java.util.function.Consumer<LivePartyRoom> action) {
		if (ctx.roomId == null) {
			return;
		}
		LivePartyRoom room = manager.room(ctx.roomId);
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
	private void withOwnedRoom(Ctx ctx, java.util.function.Consumer<LivePartyRoom> action) {
		if (ctx.roomId == null) {
			return;
		}
		LivePartyRoom room = manager.room(ctx.roomId);
		if (room == null) {
			return;
		}
		if (!manager.ownsRoom(ctx.roomId)) {
			log.info("Party V2: refusing authoritative action on {} — no longer owned here", ctx.roomId);
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
			ctx.session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
		}
		catch (Exception e) {
			log.debug("Party V2: send to {} failed: {}", ctx.session.getId(), e.toString());
		}
	}

	/** Per-connection state: the (guarded) session, its assigned member id, identity and current room. */
	private static final class Ctx {
		final WebSocketSession session;
		volatile long memberId;
		volatile long accountHash;
		volatile String name;
		volatile String roomId;

		Ctx(WebSocketSession session) {
			this.session = session;
		}
	}
}

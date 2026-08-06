package net.osparty.api.party.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.osparty.api.transport.Mux;
import net.osparty.api.transport.SocketSession;
import net.osparty.api.party.PartyFrameHandler;
import net.osparty.api.party.netty.SocketPathHandler.Route;
import net.osparty.api.web.ws.BoardBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges one Netty channel to whichever protocols it carries: the live party
 * ({@link PartyFrameHandler}) and the ad board ({@link BoardBroadcaster}) together on {@code /api/ws}, or
 * the board alone on the endpoint it used to have to itself.
 *
 * <p>Sharable: one instance serves every connection, and everything per-connection hangs off the channel.
 * The connection is built on handshake completion rather than on channel activation, because until then
 * there is no WebSocket and no request path to say which protocols were asked for or whether the client
 * named a node.
 *
 * <p>On a merged connection each protocol gets its own {@link SocketSession} over the shared channel, tagged
 * so its writes are distinguishable, and each inbound frame is routed by the {@link Mux} byte in front of
 * it. Neither protocol is aware of the arrangement.
 */
@ChannelHandler.Sharable
final class NettySocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
	private static final Logger log = LoggerFactory.getLogger(NettySocketHandler.class);

	private static final AttributeKey<Conn> CONN = AttributeKey.valueOf("partyConn");

	private final PartyFrameHandler frames;
	/** The ad board, sharing every connection with the live party so a client needs one rather than two. */
	private final BoardBroadcaster board;
	private final LongAdder dropped;
	/**
	 * Open connections per endpoint.
	 *
	 * <p>What decides when the board's own endpoint can be retired. Released plugins update on their own
	 * schedule and 1.0.50 falls back to it on its own initiative, so the only honest answer to "is anyone
	 * still on the old one" is to count. Dropping it on a guess disconnects the users who have not updated,
	 * and does it silently — they would simply stop being able to search.
	 */
	private final EnumMap<Route, AtomicInteger> open = new EnumMap<>(Route.class);
	/** Observes how fast clients talk and connect. Currently records; see {@link SocketRateLimiter}. */
	private final SocketRateLimiter limiter;

	NettySocketHandler(PartyFrameHandler frames, BoardBroadcaster board, LongAdder dropped,
		io.micrometer.core.instrument.MeterRegistry meters, SocketRateLimiter limiter) {
		this.frames = frames;
		this.board = board;
		this.dropped = dropped;
		this.limiter = limiter;
		for (Route route : new Route[] { Route.BOARD, Route.MUX }) {
			AtomicInteger count = new AtomicInteger();
			open.put(route, count);
			if (meters != null) {
				io.micrometer.core.instrument.Gauge
					.builder("osparty.ws.connections", count, AtomicInteger::get)
					.description("Open WebSocket connections, by the endpoint they arrived on")
					.tag("endpoint", route.name().toLowerCase(java.util.Locale.ROOT))
					.register(meters);
			}
		}
	}

	/**
	 * The sessions one channel carries: the merged endpoint's pair, whose frames all begin with a
	 * {@link Mux} tag, or the board alone, whose frames are untagged.
	 *
	 * <p>Either may be null if that protocol is switched off in this deployment. That leaves its half of the
	 * connection unopened rather than failing the handshake, because the other half is still worth serving.
	 *
	 * <p>{@code tagged} comes from the path rather than from which sessions exist. A merged connection whose
	 * other protocol is switched off is still a merged connection, and reading its frames as untagged would
	 * feed the tag byte to a JSON parser.
	 */
	private record Conn(SocketSession board, SocketSession live, boolean tagged, Route route) {
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (!(evt instanceof WebSocketServerProtocolHandler.HandshakeComplete complete)) {
			super.userEventTriggered(ctx, evt);
			return;
		}
		String path = complete.requestUri();
		Route route = SocketPathHandler.route(path);
		boolean tagged = route == Route.MUX;
		// Not lossy: board frames are deltas, and a skipped one leaves the client quietly out of date. Live
		// updates are, because the next one supersedes whatever was dropped.
		SocketSession boardSession = board == null ? null
			: new NettySocketSession(ctx.channel(), path, dropped,
				tagged ? Mux.BOARD : NettySocketSession.UNTAGGED, false);
		// The board's own endpoint carries discovery and nothing else; a live session on it would have no
		// way to be addressed.
		SocketSession liveSession = frames == null || !tagged ? null
			: new NettySocketSession(ctx.channel(), path, dropped, Mux.LIVE, true);

		String address = clientIp(ctx, complete.requestHeaders());
		if (limiter != null && !limiter.allowConnect(address)) {
			ctx.close();
			return;
		}
		ctx.channel().attr(CONN).set(new Conn(boardSession, liveSession, tagged, route));
		open.get(route).incrementAndGet();
		// After the attribute is set, both of them: opening a session can send, and a send on a channel whose
		// connection is not yet recorded would be answered by a close callback that finds nothing to clean up.
		// Settled during the handshake by SocketPathHandler, and null for every client that presented no
		// credential. Both protocols take it as given: from here on identity is something this connection
		// proved, not something its frames claim.
		Long authenticated = ctx.channel().attr(SocketPathHandler.AUTH_ACCOUNT).get();
		// Client-reported and unverified, same footing as X-OSParty-Client -- used only as a fresh
		// enrolment's initial device label, never to decide anything.
		String deviceLabel = complete.requestHeaders() == null ? null
			: complete.requestHeaders().get("x-osparty-device");
		if (boardSession != null) {
			board.onOpen(boardSession, address, authenticated, deviceLabel);
		}
		if (liveSession != null) {
			frames.onOpen(liveSession, authenticated);
		}
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
		Conn conn = ctx.channel().attr(CONN).get();
		// Ping, pong and close are answered by WebSocketServerProtocolHandler. Clients send binary; text is
		// still read so an older one is not silently ignored.
		if (conn == null || !(frame instanceof BinaryWebSocketFrame || frame instanceof TextWebSocketFrame)) {
			return;
		}
		// Counted once per frame, before the mux split, so the ceiling is on what the connection sends rather
		// than on what any one protocol on it does.
		if (limiter != null && !limiter.allowFrame(ctx.channel().id().asLongText())) {
			return;
		}
		ByteBuf content = frame.content();
		int length = content.readableBytes();
		if (!conn.tagged()) {
			byte[] payload = new byte[length];
			content.getBytes(content.readerIndex(), payload);
			dispatch(conn.board(), false, payload);
			return;
		}
		// A tag and nothing else is not a frame; a tag we do not know belongs to neither protocol.
		if (length < 2) {
			return;
		}
		byte tag = content.getByte(content.readerIndex());
		boolean live = tag == Mux.LIVE;
		if (!live && tag != Mux.BOARD) {
			return;
		}
		byte[] payload = new byte[length - 1];
		content.getBytes(content.readerIndex() + 1, payload);
		dispatch(live ? conn.live() : conn.board(), live, payload);
	}

	/**
	 * Hand one frame to the protocol it belongs to. The live handler takes the bytes as they arrived —
	 * Jackson reads UTF-8 directly, and decoding first would add a pass over every frame for nothing — while
	 * the board still parses from a String.
	 */
	private void dispatch(SocketSession session, boolean live, byte[] payload) {
		if (session == null) {
			return;
		}
		if (live) {
			frames.onMessage(session.id(), payload);
		}
		else {
			board.onMessage(session.id(), new String(payload, StandardCharsets.UTF_8));
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		if (limiter != null) {
			limiter.forget(ctx.channel().id().asLongText());
		}
		Conn conn = ctx.channel().attr(CONN).getAndSet(null);
		if (conn != null) {
			AtomicInteger count = open.get(conn.route());
			if (count != null) {
				count.decrementAndGet();
			}
			if (conn.board() != null) {
				board.onClose(conn.board().id(), "channel inactive");
			}
			if (conn.live() != null) {
				frames.onClose(conn.live().id(), "channel inactive");
			}
		}
		super.channelInactive(ctx);
	}

	/**
	 * The client's address, which the ad board uses to rate-limit reports.
	 *
	 * <p>The peer address alone is the ingress, not the client: every connection arrives through Traefik, so
	 * anything keyed on it is keyed on one value shared by the entire userbase -- a per-IP limit that either
	 * throttles everyone at once or nobody. {@code X-Forwarded-For} is what carries the real address.
	 *
	 * <p>Only the <em>last</em> entry is read, and only that one is trustworthy. The header is a list the
	 * client can start: a client sending {@code X-Forwarded-For: 1.2.3.4} has Traefik append the address it
	 * actually came from, leaving the forged value first and the real one last. Taking the first entry -- the
	 * usual reading, and the one that means "the original client" behind proxies you trust -- would let any
	 * client pick its own rate-limit bucket, which is worse than sharing one.
	 *
	 * <p>This holds only because nothing but the ingress can reach this port: the Netty listener is behind a
	 * ClusterIP service with no route from outside the cluster. A deployment that exposed it directly would
	 * have to stop reading the header at all, since then there is no hop that is guaranteed to have appended.
	 */
	private static String clientIp(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpHeaders headers) {
		String forwarded = headers == null ? null : headers.get("x-forwarded-for");
		if (forwarded != null && !forwarded.isBlank()) {
			int last = forwarded.lastIndexOf(',');
			String address = (last < 0 ? forwarded : forwarded.substring(last + 1)).trim();
			if (!address.isEmpty()) {
				return address;
			}
		}
		return ctx.channel().remoteAddress() instanceof java.net.InetSocketAddress address
			? address.getAddress().getHostAddress() : null;
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.debug("Party netty: closing {} after {}", ctx.channel().id().asShortText(), cause.toString());
		ctx.close();
	}
}

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

	NettySocketHandler(PartyFrameHandler frames, BoardBroadcaster board, LongAdder dropped,
		io.micrometer.core.instrument.MeterRegistry meters) {
		this.frames = frames;
		this.board = board;
		this.dropped = dropped;
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

		ctx.channel().attr(CONN).set(new Conn(boardSession, liveSession, tagged, route));
		open.get(route).incrementAndGet();
		// After the attribute is set, both of them: opening a session can send, and a send on a channel whose
		// connection is not yet recorded would be answered by a close callback that finds nothing to clean up.
		if (boardSession != null) {
			board.onOpen(boardSession, clientIp(ctx));
		}
		if (liveSession != null) {
			frames.onOpen(liveSession);
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

	/** The peer address, which the ad board uses to rate-limit reports. */
	private static String clientIp(ChannelHandlerContext ctx) {
		return ctx.channel().remoteAddress() instanceof java.net.InetSocketAddress address
			? address.getAddress().getHostAddress() : null;
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.debug("Party netty: closing {} after {}", ctx.channel().id().asShortText(), cause.toString());
		ctx.close();
	}
}

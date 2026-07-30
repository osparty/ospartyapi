package net.osparty.api.v2.netty;

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
import net.osparty.api.transport.PartySession;
import net.osparty.api.v2.PartyV2FrameHandler;
import net.osparty.api.v2.netty.PartyV2PathFilter.Route;
import net.osparty.api.web.ws.PartyBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges one Netty channel to whichever protocols it carries: the live party
 * ({@link PartyV2FrameHandler}), the ad board ({@link PartyBroadcaster}), or both at once.
 *
 * <p>Sharable: one instance serves every connection, and everything per-connection hangs off the channel.
 * The connection is built on handshake completion rather than on channel activation, because until then
 * there is no WebSocket and no request path to say which protocols were asked for or whether the client
 * named a node.
 *
 * <p>On a merged connection each protocol gets its own {@link PartySession} over the shared channel, tagged
 * so its writes are distinguishable, and each inbound frame is routed by the {@link Mux} byte in front of
 * it. Neither protocol is aware of the arrangement.
 */
@ChannelHandler.Sharable
final class PartyV2NettyHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
	private static final Logger log = LoggerFactory.getLogger(PartyV2NettyHandler.class);

	private static final AttributeKey<Conn> CONN = AttributeKey.valueOf("partyConn");

	private final PartyV2FrameHandler frames;
	/** The ad board, served on the same server so a client needs one connection rather than two. */
	private final PartyBroadcaster board;
	private final LongAdder dropped;
	/**
	 * Open connections per endpoint.
	 *
	 * <p>What decides when the single-protocol endpoints can be retired. A client picks its endpoint from
	 * what this deployment says it can do, and released plugins update on their own schedule, so the only
	 * honest answer to "is anyone still on the old one" is to count. Dropping them on a guess disconnects
	 * every user who has not updated, and does it silently — they would simply stop being able to connect.
	 */
	private final EnumMap<Route, AtomicInteger> open = new EnumMap<>(Route.class);

	PartyV2NettyHandler(PartyV2FrameHandler frames, PartyBroadcaster board, LongAdder dropped,
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
	 * The sessions one channel carries: either a single protocol, whose frames are untagged, or the merged
	 * endpoint, whose frames all carry a channel byte.
	 *
	 * <p>{@code tagged} comes from the path rather than from which sessions exist. A merged connection whose
	 * other protocol is switched off is still a merged connection, and reading its frames as untagged would
	 * feed the tag byte to a JSON parser.
	 */
	private record Conn(PartySession board, PartySession live, boolean tagged, Route route) {
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (!(evt instanceof WebSocketServerProtocolHandler.HandshakeComplete complete)) {
			super.userEventTriggered(ctx, evt);
			return;
		}
		String path = complete.requestUri();
		Route route = PartyV2PathFilter.route(path);
		// A protocol that is switched off leaves its half of the connection unopened rather than failing the
		// handshake: the other half is still worth serving.
		boolean wantsBoard = board != null;
		boolean wantsLive = frames != null && route == Route.MUX;
		boolean tagged = route == Route.MUX;

		PartySession boardSession = null;
		PartySession liveSession = null;
		if (wantsBoard) {
			// Not lossy: board frames are deltas, and a skipped one leaves the client quietly out of date.
			boardSession = new NettyPartySession(ctx.channel(), path, dropped,
				tagged ? Mux.BOARD : NettyPartySession.UNTAGGED, false);
		}
		if (wantsLive) {
			liveSession = new NettyPartySession(ctx.channel(), path, dropped,
				tagged ? Mux.LIVE : NettyPartySession.UNTAGGED, true);
		}
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
			boolean live = conn.live() != null;
			dispatch(live ? conn.live() : conn.board(), live, payload);
			return;
		}
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
	private void dispatch(PartySession session, boolean live, byte[] payload) {
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

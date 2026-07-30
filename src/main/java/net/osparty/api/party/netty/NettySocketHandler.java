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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.osparty.api.transport.Mux;
import net.osparty.api.transport.PartySession;
import net.osparty.api.party.PartyFrameHandler;
import net.osparty.api.web.ws.PartyBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges one Netty channel to the two protocols it carries: the live party
 * ({@link PartyFrameHandler}) and the ad board ({@link PartyBroadcaster}).
 *
 * <p>Sharable: one instance serves every connection, and everything per-connection hangs off the channel.
 * The connection is built on handshake completion rather than on channel activation, because until then
 * there is no WebSocket and no request path to say whether the client named a node.
 *
 * <p>Each protocol gets its own {@link PartySession} over the shared channel, tagged so its writes are
 * distinguishable, and each inbound frame is routed by the {@link Mux} byte in front of it. Neither
 * protocol is aware of the arrangement.
 */
@ChannelHandler.Sharable
final class NettySocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
	private static final Logger log = LoggerFactory.getLogger(NettySocketHandler.class);

	private static final AttributeKey<Conn> CONN = AttributeKey.valueOf("partyConn");

	private final PartyFrameHandler frames;
	/** The ad board, sharing every connection with the live party so a client needs one rather than two. */
	private final PartyBroadcaster board;
	private final LongAdder dropped;
	/** Open connections. One endpoint now, so one number. */
	private final AtomicInteger open = new AtomicInteger();

	NettySocketHandler(PartyFrameHandler frames, PartyBroadcaster board, LongAdder dropped,
		io.micrometer.core.instrument.MeterRegistry meters) {
		this.frames = frames;
		this.board = board;
		this.dropped = dropped;
		if (meters != null) {
			io.micrometer.core.instrument.Gauge
				.builder("osparty.ws.connections", open, AtomicInteger::get)
				.description("Open WebSocket connections")
				.register(meters);
		}
	}

	/**
	 * The two sessions one channel carries, one per protocol. Every frame on it — in both directions —
	 * begins with a {@link Mux} tag saying which.
	 *
	 * <p>Either may be null if that protocol is switched off in this deployment. That leaves its half of the
	 * connection unopened rather than failing the handshake, because the other half is still worth serving.
	 */
	private record Conn(PartySession board, PartySession live) {
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (!(evt instanceof WebSocketServerProtocolHandler.HandshakeComplete complete)) {
			super.userEventTriggered(ctx, evt);
			return;
		}
		String path = complete.requestUri();
		// Not lossy: board frames are deltas, and a skipped one leaves the client quietly out of date. Live
		// updates are, because the next one supersedes whatever was dropped.
		PartySession boardSession = board == null ? null
			: new NettyPartySession(ctx.channel(), path, dropped, Mux.BOARD, false);
		PartySession liveSession = frames == null ? null
			: new NettyPartySession(ctx.channel(), path, dropped, Mux.LIVE, true);

		ctx.channel().attr(CONN).set(new Conn(boardSession, liveSession));
		open.incrementAndGet();
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
			open.decrementAndGet();
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

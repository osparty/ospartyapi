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
import java.util.concurrent.atomic.LongAdder;
import net.osparty.api.transport.PartySession;
import net.osparty.api.v2.PartyV2FrameHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges one Netty channel to the transport-neutral {@link PartyV2FrameHandler}.
 *
 * <p>Sharable: one instance serves every connection, and everything per-connection hangs off the channel.
 * The session is created on handshake completion rather than on channel activation, because until then
 * there is no WebSocket and no request path to tell a node-hinted client from a plain one.
 */
@ChannelHandler.Sharable
final class PartyV2NettyHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
	private static final Logger log = LoggerFactory.getLogger(PartyV2NettyHandler.class);

	private static final AttributeKey<PartySession> SESSION = AttributeKey.valueOf("partyV2Session");
	/** Which protocol this channel speaks, decided once at the handshake from the path it arrived on. */
	private static final AttributeKey<Boolean> BOARD = AttributeKey.valueOf("partyAdBoard");

	private final PartyV2FrameHandler frames;
	/** The ad board, served on the same server so a client needs one connection rather than two. */
	private final net.osparty.api.web.ws.PartyBroadcaster board;
	private final LongAdder dropped;

	PartyV2NettyHandler(PartyV2FrameHandler frames, net.osparty.api.web.ws.PartyBroadcaster board,
		LongAdder dropped) {
		this.frames = frames;
		this.board = board;
		this.dropped = dropped;
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete complete) {
			NettyPartySession session = new NettyPartySession(ctx.channel(), complete.requestUri(), dropped);
			ctx.channel().attr(SESSION).set(session);
			// The path decides which protocol this connection speaks. Both are served here so that a
			// client can eventually hold one socket instead of two; until it does, this is simply the
			// same two endpoints on a cheaper server.
			boolean adBoard = complete.requestUri() != null
				&& complete.requestUri().startsWith(PartyV2PathFilter.BOARD_PATH);
			ctx.channel().attr(BOARD).set(adBoard);
			if (adBoard) {
				board.onOpen(session, clientIp(ctx));
			}
			else {
				frames.onOpen(session);
			}
			return;
		}
		super.userEventTriggered(ctx, evt);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
		PartySession session = ctx.channel().attr(SESSION).get();
		// Ping, pong and close are answered by WebSocketServerProtocolHandler. Clients send binary; text is
		// still read so an older one is not silently ignored.
		if (session == null || !(frame instanceof BinaryWebSocketFrame || frame instanceof TextWebSocketFrame)) {
			return;
		}
		ByteBuf content = frame.content();
		byte[] payload = new byte[content.readableBytes()];
		content.getBytes(content.readerIndex(), payload);
		if (Boolean.TRUE.equals(ctx.channel().attr(BOARD).get())) {
			board.onMessage(session.id(), new String(payload, java.nio.charset.StandardCharsets.UTF_8));
		}
		else {
			frames.onMessage(session.id(), payload);
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		PartySession session = ctx.channel().attr(SESSION).getAndSet(null);
		if (session != null) {
			if (Boolean.TRUE.equals(ctx.channel().attr(BOARD).get())) {
				board.onClose(session.id(), "channel inactive");
			}
			else {
				frames.onClose(session.id(), "channel inactive");
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
		log.debug("Party V2 netty: closing {} after {}", ctx.channel().id().asShortText(), cause.toString());
		ctx.close();
	}
}

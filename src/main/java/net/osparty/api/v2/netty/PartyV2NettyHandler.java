package net.osparty.api.v2.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import java.util.concurrent.atomic.LongAdder;
import net.osparty.api.v2.PartySession;
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

	private final PartyV2FrameHandler frames;
	private final LongAdder dropped;

	PartyV2NettyHandler(PartyV2FrameHandler frames, LongAdder dropped) {
		this.frames = frames;
		this.dropped = dropped;
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete complete) {
			NettyPartySession session = new NettyPartySession(ctx.channel(), complete.requestUri(), dropped);
			ctx.channel().attr(SESSION).set(session);
			frames.onOpen(session);
			return;
		}
		super.userEventTriggered(ctx, evt);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
		PartySession session = ctx.channel().attr(SESSION).get();
		if (session == null || !(frame instanceof TextWebSocketFrame text)) {
			// Ping, pong and close are answered by WebSocketServerProtocolHandler; binary is not spoken yet.
			return;
		}
		frames.onMessage(session.id(), text.text());
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		PartySession session = ctx.channel().attr(SESSION).getAndSet(null);
		if (session != null) {
			frames.onClose(session.id(), "channel inactive");
		}
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.debug("Party V2 netty: closing {} after {}", ctx.channel().id().asShortText(), cause.toString());
		ctx.close();
	}
}

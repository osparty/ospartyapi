package net.osparty.api.v2.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.net.URI;

/**
 * Answers anything that is not a live-party upgrade, so the WebSocket handler behind it can accept every
 * path it is given.
 *
 * <p>Two forms are served — {@code /api/v2/ws/party} and the node-hinted
 * {@code /n/{nodeId}/api/v2/ws/party} — and no single prefix covers both, which is why the check is here
 * rather than in {@code WebSocketServerProtocolHandler}'s own path matching. Everything else gets a 404:
 * this port carries the live socket and nothing else, and the ingress is what puts it behind a hostname.
 */
final class PartyV2PathFilter extends ChannelInboundHandlerAdapter {
	private static final String WS_PATH = "/api/v2/ws/party";

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (!(msg instanceof HttpRequest request)) {
			ctx.fireChannelRead(msg);
			return;
		}
		if (accepts(request.uri())) {
			ctx.fireChannelRead(msg);
			return;
		}
		ReferenceCountUtil.release(msg);
		FullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, ctx.alloc().buffer(0));
		response.headers().set("content-length", 0);
		ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
	}

	/** Whether {@code uri} names the live socket, in either its plain or its node-hinted form. */
	static boolean accepts(String uri) {
		if (uri == null) {
			return false;
		}
		String path;
		try {
			// Query strings are not used, but a client that appends one must not be turned away for it.
			path = URI.create(uri).getPath();
		}
		catch (IllegalArgumentException e) {
			return false;
		}
		if (path == null) {
			return false;
		}
		if (path.equals(WS_PATH)) {
			return true;
		}
		// /n/{nodeId}/api/v2/ws/party — one segment of node id, and nothing else before the path.
		if (!path.startsWith("/n/") || !path.endsWith(WS_PATH)) {
			return false;
		}
		String nodeId = path.substring("/n/".length(), path.length() - WS_PATH.length());
		return !nodeId.isEmpty() && nodeId.indexOf('/') < 0;
	}
}

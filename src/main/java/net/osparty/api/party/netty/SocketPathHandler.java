package net.osparty.api.party.netty;

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
 * Answers anything that is not this server's WebSocket endpoint, so the handler behind it can accept every
 * path it is given.
 *
 * <p>One endpoint, optionally behind a {@code /n/{nodeId}} segment that pins the connection to one pod. No
 * single prefix covers that shape, which is why the check is here rather than in
 * {@code WebSocketServerProtocolHandler}'s own path matching. Everything else gets a 404: this port carries
 * sockets and nothing else, and the ingress is what puts it behind a hostname.
 *
 * <p>The ad board and the live party each had a path of their own once. Both are carried on this one now,
 * demultiplexed per frame by {@link net.osparty.api.transport.Mux}, so a client costs the ingress a single
 * socket whether or not it is in a party.
 */
final class SocketPathHandler extends ChannelInboundHandlerAdapter {
	/** Both protocols on one connection, demultiplexed by {@link net.osparty.api.transport.Mux}. */
	private static final String PATH = "/api/ws";

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

	/** Whether {@code uri} names this server's endpoint, in either of its forms. */
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
		if (path.startsWith("/n/")) {
			// One segment of node id, and nothing else before the endpoint.
			int end = path.indexOf('/', "/n/".length());
			if (end < 0 || end == "/n/".length()) {
				return false;
			}
			path = path.substring(end);
		}
		return PATH.equals(path);
	}
}

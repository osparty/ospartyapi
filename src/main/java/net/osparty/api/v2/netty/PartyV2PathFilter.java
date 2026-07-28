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
 * Answers anything that is not one of this server's WebSocket endpoints, so the handler behind it can accept
 * every path it is given, and works out which protocol a path asks for.
 *
 * <p>Three endpoints are served, each optionally behind a {@code /n/{nodeId}} segment that pins the
 * connection to one pod. No single prefix covers that shape, which is why the check is here rather than in
 * {@code WebSocketServerProtocolHandler}'s own path matching. Everything else gets a 404: this port carries
 * sockets and nothing else, and the ingress is what puts it behind a hostname.
 */
final class PartyV2PathFilter extends ChannelInboundHandlerAdapter {
	/** The V2 live party on its own connection. */
	private static final String LIVE_PATH = "/api/v2/ws/party";
	/** The V1 ad board on its own connection. */
	private static final String BOARD_PATH = "/api/v1/ws/parties";
	/** Both protocols on one connection, demultiplexed by {@link net.osparty.api.transport.Mux}. */
	private static final String MUX_PATH = "/api/ws";

	/** Which protocols a connection carries, decided once from the path it arrived on. */
	enum Route {
		/** Not an endpoint of this server. */
		NONE,
		BOARD,
		LIVE,
		/** Both, tagged per frame. */
		MUX
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (!(msg instanceof HttpRequest request)) {
			ctx.fireChannelRead(msg);
			return;
		}
		if (route(request.uri()) != Route.NONE) {
			ctx.fireChannelRead(msg);
			return;
		}
		ReferenceCountUtil.release(msg);
		FullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, ctx.alloc().buffer(0));
		response.headers().set("content-length", 0);
		ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
	}

	/** Whether {@code uri} names an endpoint this server serves, in any of its forms. */
	static boolean accepts(String uri) {
		return route(uri) != Route.NONE;
	}

	/** Which protocols {@code uri} asks for, or {@link Route#NONE} if it names no endpoint here. */
	static Route route(String uri) {
		if (uri == null) {
			return Route.NONE;
		}
		String path;
		try {
			// Query strings are not used, but a client that appends one must not be turned away for it.
			path = URI.create(uri).getPath();
		}
		catch (IllegalArgumentException e) {
			return Route.NONE;
		}
		if (path == null) {
			return Route.NONE;
		}
		if (path.startsWith("/n/")) {
			// One segment of node id, and nothing else before the endpoint.
			int end = path.indexOf('/', "/n/".length());
			if (end < 0 || end == "/n/".length()) {
				return Route.NONE;
			}
			path = path.substring(end);
		}
		switch (path) {
			case LIVE_PATH:
				return Route.LIVE;
			case BOARD_PATH:
				return Route.BOARD;
			case MUX_PATH:
				return Route.MUX;
			default:
				return Route.NONE;
		}
	}
}

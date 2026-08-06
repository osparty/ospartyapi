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
 * Answers anything that is not one of this server's WebSocket endpoints, so the handler behind it can accept
 * every path it is given, and works out which protocols a path asks for.
 *
 * <p>The endpoint is {@code /api/ws}, optionally behind a {@code /n/{nodeId}} segment that pins the
 * connection to one pod. No single prefix covers that shape, which is why the check is here rather than in
 * {@code WebSocketServerProtocolHandler}'s own path matching. Everything else gets a 404: this port carries
 * sockets and nothing else, and the ingress is what puts it behind a hostname.
 *
 * <p>The ad board and the live party each had a path of their own once. Both are carried on the one now,
 * demultiplexed per frame by {@link net.osparty.api.transport.Mux}, so a client costs the ingress a single
 * socket whether or not it is in a party. The board's old path is still served ({@link Route#BOARD}) because
 * plugin 1.0.50 falls back to it when the merged socket fails it repeatedly — a server that stops serving it
 * turns a bad connection into a client with no discovery at all until the user restarts. The live party's
 * old path is not: no released plugin ever dialled it on its own.
 */
final class SocketPathHandler extends ChannelInboundHandlerAdapter {
	/** Both protocols on one connection, demultiplexed by {@link net.osparty.api.transport.Mux}. */
	private static final String PATH = "/api/ws";
	/** The ad board alone, untagged, as every version of this service served it before the merge. */
	private static final String BOARD_PATH = "/api/v1/ws/parties";

	/** The credential a client presents to say which account it is. Absent for every pre-auth client. */
	private static final String AUTH_HEADER = "x-osparty-auth";

	/**
	 * The account this connection proved, set before the upgrade completes and true for its whole life.
	 *
	 * <p>Read rather than the account hash in a frame wherever identity decides anything. A frame's hash is
	 * whatever the client typed; this is what a credential we issued resolved to.
	 */
	static final io.netty.util.AttributeKey<Long> AUTH_ACCOUNT =
		io.netty.util.AttributeKey.valueOf("ospartyAuthAccount");

	/** The token itself, kept only to mark it as used once the connection is established. */
	static final io.netty.util.AttributeKey<String> AUTH_TOKEN =
		io.netty.util.AttributeKey.valueOf("ospartyAuthToken");

	private final net.osparty.api.service.AccountAuthService auth;

	SocketPathHandler(net.osparty.api.service.AccountAuthService auth) {
		this.auth = auth;
	}

	/** Which protocols a connection carries, decided once from the path it arrived on. */
	enum Route {
		/** Not an endpoint of this server. */
		NONE,
		/** The ad board alone, frames untagged. */
		BOARD,
		/** Both, tagged per frame. */
		MUX
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (!(msg instanceof HttpRequest request)) {
			ctx.fireChannelRead(msg);
			return;
		}
		if (route(request.uri()) == Route.NONE) {
			refuse(ctx, msg, HttpResponseStatus.NOT_FOUND);
			return;
		}
		if (!originAllowed(request.headers().get("origin"))) {
			refuse(ctx, msg, HttpResponseStatus.FORBIDDEN);
			return;
		}
		authenticate(ctx, request.headers().get(AUTH_HEADER));
		ctx.fireChannelRead(msg);
	}

	/**
	 * Resolve the presented credential, before the upgrade completes, and record what it proved on the
	 * channel.
	 *
	 * <p>Here rather than in a frame handler for two reasons. This handler is one instance per connection, so
	 * it can hold per-connection state, where the frame handler is {@code @Sharable} and cannot. And it runs
	 * while this is still an HTTP request, so the credential rides a header -- not a query string, which
	 * proxies and access logs keep, and not a frame, which would leave a window where the connection is
	 * established and its identity is not yet settled.
	 *
	 * <p>A bad token is not refused. It is simply not believed: the connection continues with no account
	 * attached and gets whatever an unauthenticated client gets. Refusing would make an expired or
	 * server-side-revoked credential look like an outage to a client that cannot tell the difference, and
	 * every client from before this existed sends nothing here anyway.
	 */
	private void authenticate(ChannelHandlerContext ctx, String token) {
		if (auth == null || token == null || token.isBlank()) {
			return;
		}
		auth.accountFor(token).ifPresent(accountHash -> {
			// The one place a credential's use is ever recorded. Without this a device's "last seen" never
			// moves past the moment it was issued or coupled, which is exactly the number someone deciding
			// whether to revoke a device they don't recognise needs to not be stale.
			auth.touch(token);
			ctx.channel().attr(AUTH_ACCOUNT).set(accountHash);
			ctx.channel().attr(AUTH_TOKEN).set(token);
		});
	}

	private static void refuse(ChannelHandlerContext ctx, Object msg, HttpResponseStatus status) {
		ReferenceCountUtil.release(msg);
		FullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1, status, ctx.alloc().buffer(0));
		response.headers().set("content-length", 0);
		ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
	}

	/**
	 * Whether a handshake carrying this {@code Origin} may proceed.
	 *
	 * <p>Netty does not check {@code Origin} and nothing configured one, so any web page a player visited
	 * could open a socket to this service and speak the protocol as them, in the background, from their
	 * address. That is a different adversary from someone running a script deliberately -- it needs no
	 * intent from the victim beyond loading a page -- and it is the one a check here removes entirely.
	 *
	 * <p>Absent means allowed, and that is the whole reason this is safe to ship: browsers always send the
	 * header on a cross-origin WebSocket, and non-browser clients never do. The plugin is one of the latter
	 * -- OkHttp sets no {@code Origin} -- so every client in the wild passes, while a page in a browser has
	 * to name itself and is turned away. It buys nothing against a script, which simply omits the header;
	 * the browser case is what it is for.
	 */
	private static boolean originAllowed(String origin) {
		return origin == null || origin.isBlank();
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
		if (PATH.equals(path)) {
			return Route.MUX;
		}
		return BOARD_PATH.equals(path) ? Route.BOARD : Route.NONE;
	}
}

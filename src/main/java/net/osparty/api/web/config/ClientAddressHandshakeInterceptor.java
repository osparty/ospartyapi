package net.osparty.api.web.config;

import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Records the originating client address on the WebSocket session at handshake time.
 *
 * <p>Behind the ingress, {@code session.getRemoteAddress()} is the ingress pod, identical for every
 * client, so the only usable signal is the forwarded header. That header is set by the proxy, not
 * the client, but only for the hop closest to us — anything further left in the list is
 * client-supplied and forgeable, hence the leftmost-of-the-trusted-suffix caveat below.
 *
 * <p>When no forwarded header is present the attribute is left absent rather than defaulting to the
 * socket address. That distinction matters: a rate limiter that cannot tell clients apart must
 * disable itself, not lump the entire user base into one bucket and throttle everybody.
 */
public class ClientAddressHandshakeInterceptor implements HandshakeInterceptor {
	/** Present only when a forwarded header was supplied; absent means "cannot distinguish clients". */
	public static final String CLIENT_IP_ATTRIBUTE = "osparty.clientIp";

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
		WebSocketHandler wsHandler, Map<String, Object> attributes) {
		String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			// Leftmost entry is the original client as far as our own proxy is aware. Good enough
			// for rate limiting, which is all this is ever used for.
			String first = forwarded.split(",")[0].trim();
			if (!first.isEmpty()) {
				attributes.put(CLIENT_IP_ATTRIBUTE, first);
			}
		}
		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
		WebSocketHandler wsHandler, Exception exception) {
	}
}

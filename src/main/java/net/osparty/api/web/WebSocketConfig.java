package net.osparty.api.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Exposes the live party-list socket at {@code /api/v1/ws/parties}. The
 * {@code /api/v1} prefix here is literal — {@link WebConfig}'s prefix only
 * applies to REST controllers, not WebSocket handlers — but matching it keeps the
 * endpoint under the same versioned namespace, so a reverse proxy forwarding
 * {@code /api/**} to the app carries the socket too (just allow the
 * {@code Upgrade} header on that path).
 *
 * <p>{@code setAllowedOrigins("*")} because the RuneLite client is not a browser
 * and sends no {@code Origin}; the default same-origin check would otherwise
 * reject it.
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "app.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketConfig implements WebSocketConfigurer
{
	public static final String WS_PATH = "/api/v1/ws/parties";

	private final PartyBroadcaster broadcaster;

	public WebSocketConfig(PartyBroadcaster broadcaster)
	{
		this.broadcaster = broadcaster;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
	{
		registry.addHandler(broadcaster, WS_PATH).setAllowedOrigins("*");
	}
}

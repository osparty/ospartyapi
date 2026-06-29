package net.osparty.api.web.config;

import net.osparty.api.web.ws.PartyBroadcaster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "app.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketConfig implements WebSocketConfigurer {
	public static final String WS_PATH = "/api/v1/ws/parties";

	private final PartyBroadcaster broadcaster;

	public WebSocketConfig(PartyBroadcaster broadcaster) {
		this.broadcaster = broadcaster;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(broadcaster, WS_PATH).setAllowedOrigins("*");
	}
}

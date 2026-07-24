package net.osparty.api.v2;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Party V2 live-party WebSocket endpoint. Entirely separate from the V1 ad-board socket
 * ({@code /api/v1/ws/parties}); this carries live in-room state (HP/prayer/spec/location/…) that the
 * plugin currently relays over RuneLite's built-in party network. See PARTY_V2_MIGRATION.md.
 *
 * <p>Gated OFF by default: nothing here loads unless {@code app.party-v2.enabled=true}, so the running
 * V1 system is unaffected while V2 is built in parallel.
 *
 * <p>P1: single-node in-memory relay (owner is always this node). P2 will additionally accept the
 * node-hint form {@code /n/{nodeId}/api/v2/ws/party} so the gateway can route a joiner straight to the
 * owning pod; the trailing path is identical.
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2WebSocketConfig implements WebSocketConfigurer {
	public static final String WS_PATH = "/api/v2/ws/party";

	private final PartyV2Handler handler;

	public PartyV2WebSocketConfig(PartyV2Handler handler) {
		this.handler = handler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, WS_PATH).setAllowedOrigins("*");
	}
}

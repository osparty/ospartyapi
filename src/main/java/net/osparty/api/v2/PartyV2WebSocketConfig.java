package net.osparty.api.v2;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
// Two conditions, so an expression rather than two annotations: V2 must be on, and this transport must be
// the one selected. With `netty` the endpoint is not registered here at all and the live socket is served
// by NettyPartyV2Server on its own port.
@ConditionalOnExpression(
	"${app.party-v2.enabled:false} and '${app.party-v2.transport:tomcat}' == 'tomcat'")
public class PartyV2WebSocketConfig implements WebSocketConfigurer {
	public static final String WS_PATH = "/api/v2/ws/party";
	/** Node-hint form (§3.2): the gateway routes the {@code {nodeId}} segment to the owning pod. */
	public static final String NODE_HINT_PATH = "/n/{nodeId}/api/v2/ws/party";

	private final PartyV2Handler handler;

	public PartyV2WebSocketConfig(PartyV2Handler handler) {
		this.handler = handler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// Both forms hit the same handler; the {nodeId} segment is a routing hint for the gateway, and in a
		// single-node/dev run it is simply ignored (the one node always owns).
		registry.addHandler(handler, WS_PATH, NODE_HINT_PATH).setAllowedOrigins("*");
	}
}

package net.osparty.api.v2.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Party V2 live-party WebSocket handler. Gated OFF by default ({@code app.party-v2.enabled}); loads only
 * alongside {@link PartyV2WebSocketConfig}, so V1 is unaffected.
 *
 * <p>P0 skeleton only: it accepts a connection and acknowledges it, but holds no room state and relays
 * nothing. P1 introduces the in-memory {@code PartyV2Manager}/{@code LivePartyRoom} model, frame
 * decode/dispatch (§8 protocol) and server-authoritative admission; P2 adds ownership + node-hint
 * routing. See PARTY_V2_MIGRATION.md.
 */
@Component
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2Handler extends TextWebSocketHandler {
	private static final Logger log = LoggerFactory.getLogger(PartyV2Handler.class);

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		log.info("Party V2 WS connected: session={} (P0 skeleton — no room state yet)", session.getId());
		session.sendMessage(new TextMessage("{\"type\":\"welcome\",\"detail\":\"party-v2 skeleton\"}"));
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		// P0: no protocol yet. P1 decodes the §8 frames (hello/host/join/state/ping/…) and dispatches
		// to the in-memory party manager.
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		log.info("Party V2 WS closed: session={} status={}", session.getId(), status);
	}
}

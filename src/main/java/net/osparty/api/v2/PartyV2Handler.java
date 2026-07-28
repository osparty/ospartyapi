package net.osparty.api.v2;

import net.osparty.api.transport.PartySession;
import net.osparty.api.transport.SpringPartySession;

import java.nio.ByteBuffer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * The servlet-container transport for Party V2: it carries bytes and does nothing else. All protocol
 * handling is in {@link PartyV2FrameHandler}, which is shared with the Netty transport.
 *
 * <p>Selected by {@code app.party-v2.transport=tomcat}, the default. The alternative exists because the
 * send path in this one is the single largest consumer of this service's CPU (PARTY_V2_OPTIMIZATION.md
 * §6.5.3) — both ship in the same image so the comparison is a config flip rather than a deploy.
 */
@Component
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2Handler extends AbstractWebSocketHandler {
	private static final int SEND_TIME_LIMIT_MS = 10_000;
	private static final int SEND_BUFFER_LIMIT = 512 * 1024;

	private final PartyV2FrameHandler frames;

	public PartyV2Handler(PartyV2FrameHandler frames) {
		this.frames = frames;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		// Sends come from the aggregator thread as well as from inbound handling, and a raw session may not
		// be written to concurrently. Netty needs no equivalent: writes there are ordered by the event loop.
		frames.onOpen(new SpringPartySession(new ConcurrentWebSocketSessionDecorator(
			session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT)));
	}

	/** Clients send binary now; text is still read so an older one is not silently ignored. */
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		frames.onMessage(session.getId(), message.asBytes());
	}

	@Override
	protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
		ByteBuffer payload = message.getPayload();
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		frames.onMessage(session.getId(), bytes);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		frames.onClose(session.getId(), String.valueOf(status));
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		try {
			session.close(CloseStatus.SERVER_ERROR);
		}
		catch (Exception ignored) {
		}
	}
}

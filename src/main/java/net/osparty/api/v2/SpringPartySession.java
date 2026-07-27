package net.osparty.api.v2;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * A {@link PartySession} backed by a Spring/servlet {@code WebSocketSession}.
 *
 * <p>The session handed in is expected to already be wrapped for concurrent sends — see
 * {@link PartyV2Handler} — because frames come from the aggregator thread as well as from the thread that
 * handled an inbound message.
 */
final class SpringPartySession implements PartySession {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SpringPartySession.class);

	private final WebSocketSession session;
	private final boolean nodeHinted;

	SpringPartySession(WebSocketSession session) {
		this.session = session;
		this.nodeHinted = session.getUri() != null && session.getUri().getPath() != null
			&& session.getUri().getPath().startsWith("/n/");
	}

	@Override
	public String id() {
		return session.getId();
	}

	@Override
	public boolean isOpen() {
		return session.isOpen();
	}

	@Override
	public void send(String json) {
		try {
			session.sendMessage(new TextMessage(json));
		}
		catch (Exception e) {
			// Swallowed on purpose: a broken peer must not unwind through the fan-out that is visiting it.
			// The close callback and the ghost sweep are what remove it.
			log.debug("Party V2: dropping send to {}: {}", session.getId(), e.toString());
		}
	}

	@Override
	public void close() {
		try {
			session.close();
		}
		catch (Exception ignored) {
		}
	}

	@Override
	public boolean nodeHinted() {
		return nodeHinted;
	}
}

package net.osparty.api.web.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.osparty.api.repository.PartyRepository;
import net.osparty.api.repository.PartyRepository.Authorization;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@ConditionalOnProperty(name = "app.ws.enabled", havingValue = "true", matchIfMissing = true)
public class PartyBroadcaster extends TextWebSocketHandler {
	private static final Logger log = LoggerFactory.getLogger(PartyBroadcaster.class);

	private static final int SEND_TIME_LIMIT_MS = 10_000;
	private static final int SEND_BUFFER_LIMIT = 512 * 1024;
	private static final PartyUpdate TTL_TOUCH = new PartyUpdate();

	private final PartyRepository store;
	private final ObjectMapper mapper;
	private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
	private final Map<String, String> hostedBy = new ConcurrentHashMap<>();
	private final Map<String, String> ownerSession = new ConcurrentHashMap<>();
	private final AtomicLong version = new AtomicLong();

	public PartyBroadcaster(PartyRepository store, ObjectMapper mapper) {
		this.store = store;
		this.mapper = mapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(
			session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
		subscribers.put(session.getId(), new Subscriber(guarded));
		log.info("WS connected: session={} remote={} (subscribers={})",
			session.getId(), remoteOf(session), subscribers.size());
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		Subscriber sub = subscribers.get(session.getId());
		if (sub == null) {
			return;
		}
		Inbound in;
		try {
			in = mapper.readValue(message.getPayload(), Inbound.class);
		}
		catch (Exception e) {
			return;
		}
		if (in.type() == null) {
			return;
		}
		switch (in.type()) {
			case "subscribe":
				handleSubscribe(sub, in);
				break;
			case "unsubscribe":
				handleUnsubscribe(sub);
				break;
			case "host":
				handleHost(sub, in);
				break;
			case "update":
				handleUpdate(sub, in);
				break;
			case "resume":
				handleResume(sub, in);
				break;
			case "unhost":
				handleUnhost(sub, in);
				break;
			case "getByCode":
				handleGetByCode(sub, in);
				break;
			case "getByHost":
				handleGetByHost(sub, in);
				break;
			default:
				break;
		}
	}

	private void handleSubscribe(Subscriber sub, Inbound in) {
		sub.activity = (in.activity() == null || in.activity().isBlank()) ? null : in.activity();
		sub.subscribed = true;
		log.info("WS subscribe: session={} activity={}", sub.session.getId(),
			sub.activity == null ? "<all>" : sub.activity);
		sendSnapshot(sub);
	}

	private void handleUnsubscribe(Subscriber sub) {
		sub.subscribed = false;
	}

	private void handleGetByCode(Subscriber sub, Inbound in) {
		String code = in.code();
		Party party = code == null ? null : store.findByInviteCode(code).orElse(null);
		send(sub, Outbound.byCode(version.get(), code, party));
	}

	private void handleGetByHost(Subscriber sub, Inbound in) {
		String host = in.host();
		Party party = host == null ? null : store.findByHost(host).orElse(null);
		send(sub, Outbound.byHost(version.get(), host, party));
	}

	private void handleHost(Subscriber sub, Inbound in) {
		if (in.request() == null) {
			sendError(sub, null, "missing request");
			return;
		}
		Party party = store.create(in.request(), in.key());
		bind(sub.session.getId(), party.getId());
		log.info("WS host: session={} party={} host={}", sub.session.getId(), party.getId(), party.getHost());
		send(sub, Outbound.hosted(version.get(), party));
	}

	private void handleUpdate(Subscriber sub, Inbound in) {
		String id = in.id();
		if (id == null) {
			sendError(sub, null, "missing id");
			return;
		}
		if (!authorizeWrite(sub, id, in.key())) {
			return;
		}
		if (store.update(id, in.patch() == null ? TTL_TOUCH : in.patch()).isEmpty()) {
			sendError(sub, id, "gone");
			unbind(sub.session.getId());
		}
	}

	private void handleResume(Subscriber sub, Inbound in) {
		String id = in.id();
		if (id == null) {
			sendError(sub, null, "missing id");
			return;
		}
		Authorization auth = store.authorize(id, in.key());
		if (auth == Authorization.NOT_FOUND) {
			send(sub, Outbound.gone(version.get(), id));
			return;
		}
		if (auth != Authorization.OK) {
			sendError(sub, id, "forbidden");
			return;
		}
		Optional<Party> party = store.update(id, TTL_TOUCH);
		if (party.isEmpty()) {
			send(sub, Outbound.gone(version.get(), id));
			return;
		}
		bind(sub.session.getId(), id);
		log.info("WS resume: session={} party={}", sub.session.getId(), id);
		send(sub, Outbound.hosted(version.get(), party.get()));
	}

	private void handleUnhost(Subscriber sub, Inbound in) {
		String id = in.id();
		if (id == null) {
			sendError(sub, null, "missing id");
			return;
		}
		if (!authorizeWrite(sub, id, in.key())) {
			return;
		}
		store.delete(id);
		unbind(sub.session.getId());
		log.info("WS unhost: session={} party={}", sub.session.getId(), id);
	}

	private boolean authorizeWrite(Subscriber sub, String id, String key) {
		if (id.equals(hostedBy.get(sub.session.getId()))) {
			return true;
		}
		Authorization auth = store.authorize(id, key);
		if (auth == Authorization.NOT_FOUND) {
			sendError(sub, id, "gone");
			return false;
		}
		if (auth != Authorization.OK) {
			sendError(sub, id, "forbidden");
			return false;
		}
		bind(sub.session.getId(), id);
		return true;
	}

	private void bind(String sessionId, String partyId) {
		hostedBy.put(sessionId, partyId);
		String previous = ownerSession.put(partyId, sessionId);
		if (previous != null && !previous.equals(sessionId)) {
			hostedBy.remove(previous, partyId);
		}
	}

	private void unbind(String sessionId) {
		String partyId = hostedBy.remove(sessionId);
		if (partyId != null) {
			ownerSession.remove(partyId, sessionId);
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		subscribers.remove(session.getId());
		unbind(session.getId());
		log.info("WS closed: session={} status={} (subscribers={})",
			session.getId(), status, subscribers.size());
	}

	@Scheduled(fixedDelayString = "${app.ws.touch-interval-ms:5000}")
	public void touchOwnedParties() {
		for (Map.Entry<String, String> entry : hostedBy.entrySet()) {
			Subscriber sub = subscribers.get(entry.getKey());
			if (sub == null || !sub.session.isOpen()) {
				continue;
			}
			if (store.update(entry.getValue(), TTL_TOUCH).isEmpty()) {
				unbind(entry.getKey());
			}
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		subscribers.remove(session.getId());
		try {
			session.close(CloseStatus.SERVER_ERROR);
		}
		catch (IOException ignored) {
		}
	}

	private void sendSnapshot(Subscriber sub) {
		List<Party> list = store.list(sub.activity);
		log.debug("WS snapshot -> {} ({} parties)", sub.session.getId(), list.size());
		send(sub, Outbound.snapshot(version.get(), list));
	}

	private void sendError(Subscriber sub, String id, String detail) {
		send(sub, Outbound.error(version.get(), id, detail));
	}

	void created(Party party) {
		fanOut(party.getActivity(), Outbound.created(version.incrementAndGet(), party));
	}

	void updated(Party party) {
		fanOut(party.getActivity(), Outbound.updated(version.incrementAndGet(), party));
	}

	void removed(String id, String activity) {
		fanOut(activity, Outbound.removed(version.incrementAndGet(), id));
	}

	private void fanOut(String activity, Outbound msg) {
		for (Subscriber sub : subscribers.values()) {
			if (sub.subscribed && (sub.activity == null || sub.activity.equals(activity))) {
				send(sub, msg);
			}
		}
	}

	private void send(Subscriber sub, Outbound msg) {
		if (!sub.session.isOpen()) {
			return;
		}
		String json;
		try {
			json = mapper.writeValueAsString(msg);
		}
		catch (Exception e) {
			log.warn("Failed to serialise {} frame", msg.type(), e);
			return;
		}
		log.debug("WS -> {} {}", sub.session.getId(), json);
		try {
			sub.session.sendMessage(new TextMessage(json));
		}
		catch (Exception e) {
			log.debug("Dropping subscriber {} after send failure: {}", sub.session.getId(), e.toString());
			subscribers.remove(sub.session.getId());
			try {
				sub.session.close(CloseStatus.SERVER_ERROR);
			}
			catch (IOException ignored) {
			}
		}
	}

	private static String remoteOf(WebSocketSession session) {
		List<String> forwarded = session.getHandshakeHeaders().get("X-Forwarded-For");
		if (forwarded != null && !forwarded.isEmpty()) {
			return forwarded.get(0);
		}
		return String.valueOf(session.getRemoteAddress());
	}

	private static final class Subscriber {
		final WebSocketSession session;
		volatile boolean subscribed;
		volatile String activity;

		Subscriber(WebSocketSession session) {
			this.session = session;
		}
	}

	record Inbound(String type, String activity, PartyRequest request, PartyUpdate patch, String id, String key,
		String code, String host) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Outbound(String type, long version, List<Party> parties, Party party, String id, String detail) {
		static Outbound snapshot(long version, List<Party> parties) {
			return new Outbound("snapshot", version, parties, null, null, null);
		}

		static Outbound created(long version, Party party) {
			return new Outbound("created", version, null, party, null, null);
		}

		static Outbound updated(long version, Party party) {
			return new Outbound("updated", version, null, party, null, null);
		}

		static Outbound removed(long version, String id) {
			return new Outbound("removed", version, null, null, id, null);
		}

		static Outbound hosted(long version, Party party) {
			return new Outbound("hosted", version, null, party, null, null);
		}

		static Outbound gone(long version, String id) {
			return new Outbound("gone", version, null, null, id, null);
		}

		static Outbound error(long version, String id, String detail) {
			return new Outbound("error", version, null, null, id, detail);
		}

		static Outbound byCode(long version, String code, Party party) {
			return new Outbound("byCode", version, null, party, code, null);
		}

		static Outbound byHost(long version, String host, Party party) {
			return new Outbound("byHost", version, null, party, host, null);
		}
	}
}

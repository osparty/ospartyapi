package net.osparty.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.osparty.api.PartyRepository;
import net.osparty.api.PartyRepository.Authorization;
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

/**
 * Pushes the open-party list to subscribed clients over a WebSocket, replacing
 * the plugin's per-client polling. On connect a client sends
 * {@code {"type":"subscribe","activity":"cox"}} (activity optional — omit for all
 * public parties) and gets a {@code snapshot} of the current ads; thereafter the
 * server pushes {@code created}/{@code updated}/{@code removed} deltas (keyed by
 * id) as the ad set changes. The deltas are produced by {@link PartyReconciler}.
 *
 * <p>Each frame carries a monotonic {@code version} (a loose ordering hint); the
 * deltas are idempotent (upsert/remove by id) and a reconnect re-sends a full
 * snapshot, so a dropped frame self-heals on the next snapshot.
 *
 * <p>Hosts also <b>write</b> over the same socket: {@code host} (create),
 * {@code update} (partial change), {@code unhost} (disband) and {@code resume}
 * (reclaim after a reconnect). The connection itself is the host's liveness — while
 * the owning session is open, {@link #touchOwnedParties()} keeps the ad's TTL
 * refreshed, so a dropped socket lets the ad lapse after its grace window unless the
 * host reconnects and {@code resume}s it (same id). Ownership is the session that
 * created/reclaimed the ad; a host key (carried on {@code host}/{@code resume}) is
 * the cross-reconnect reclaim credential. Mutations still land in the same store as
 * REST, so {@link PartyReconciler} broadcasts the resulting list changes uniformly.
 */
@Component
@ConditionalOnProperty(name = "app.ws.enabled", havingValue = "true", matchIfMissing = true)
public class PartyBroadcaster extends TextWebSocketHandler
{
	private static final Logger log = LoggerFactory.getLogger(PartyBroadcaster.class);

	/** Guard rails on a slow/stuck client: drop it rather than block the sender. */
	private static final int SEND_TIME_LIMIT_MS = 10_000;
	private static final int SEND_BUFFER_LIMIT = 512 * 1024;
	/** A no-op patch: applying it changes nothing but refreshes the ad's TTL. */
	private static final PartyUpdate TTL_TOUCH = new PartyUpdate();

	private final PartyRepository store;
	private final ObjectMapper mapper;
	private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
	/** sessionId -> the party id that session hosts (at most one). */
	private final Map<String, String> hostedBy = new ConcurrentHashMap<>();
	/** party id -> the session that currently owns it (the reverse of {@link #hostedBy}). */
	private final Map<String, String> ownerSession = new ConcurrentHashMap<>();
	private final AtomicLong version = new AtomicLong();

	public PartyBroadcaster(PartyRepository store, ObjectMapper mapper)
	{
		this.store = store;
		this.mapper = mapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session)
	{
		// The decorator serialises concurrent sends (reconciler thread + the snapshot
		// reply) per session and enforces the send time/buffer limits above.
		WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(
			session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
		subscribers.put(session.getId(), new Subscriber(guarded));
		log.info("WS connected: session={} remote={} (subscribers={})",
			session.getId(), remoteOf(session), subscribers.size());
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message)
	{
		Subscriber sub = subscribers.get(session.getId());
		if (sub == null)
		{
			return;
		}
		Inbound in;
		try
		{
			in = mapper.readValue(message.getPayload(), Inbound.class);
		}
		catch (Exception e)
		{
			return; // ignore malformed frames
		}
		if (in.type() == null)
		{
			return;
		}
		switch (in.type())
		{
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
			default:
				break; // unknown — ignore
		}
	}

	private void handleSubscribe(Subscriber sub, Inbound in)
	{
		sub.activity = (in.activity() == null || in.activity().isBlank()) ? null : in.activity();
		sub.subscribed = true;
		log.info("WS subscribe: session={} activity={}", sub.session.getId(),
			sub.activity == null ? "<all>" : sub.activity);
		sendSnapshot(sub);
	}

	/** Stop the list firehose for a connection that only needs to host (no search view). */
	private void handleUnsubscribe(Subscriber sub)
	{
		sub.subscribed = false;
	}

	/** Create an ad and bind it to this session (the session becomes its owner). */
	private void handleHost(Subscriber sub, Inbound in)
	{
		if (in.request() == null)
		{
			sendError(sub, null, "missing request");
			return;
		}
		Party party = store.create(in.request(), in.key());
		bind(sub.session.getId(), party.getId());
		log.info("WS host: session={} party={} host={}", sub.session.getId(), party.getId(), party.getHost());
		// Directed ack so the host learns the server-assigned id/inviteCode; searchers
		// pick it up from the reconciler on its next tick.
		send(sub, Outbound.hosted(version.get(), party));
	}

	/** Apply a partial update to an ad this session owns (or proves it owns via key). */
	private void handleUpdate(Subscriber sub, Inbound in)
	{
		String id = in.id();
		if (id == null)
		{
			sendError(sub, null, "missing id");
			return;
		}
		if (!authorizeWrite(sub, id, in.key()))
		{
			return;
		}
		if (store.update(id, in.patch() == null ? TTL_TOUCH : in.patch()).isEmpty())
		{
			sendError(sub, id, "gone");
			unbind(sub.session.getId());
		}
		// On success the reconciler broadcasts the resulting change; no directed ack.
	}

	/** Reclaim an ad after a reconnect: re-bind it to this session and refresh its TTL. */
	private void handleResume(Subscriber sub, Inbound in)
	{
		String id = in.id();
		if (id == null)
		{
			sendError(sub, null, "missing id");
			return;
		}
		Authorization auth = store.authorize(id, in.key());
		if (auth == Authorization.NOT_FOUND)
		{
			send(sub, Outbound.gone(version.get(), id)); // grace expired — host re-creates
			return;
		}
		if (auth != Authorization.OK)
		{
			sendError(sub, id, "forbidden");
			return;
		}
		Optional<Party> party = store.update(id, TTL_TOUCH);
		if (party.isEmpty())
		{
			send(sub, Outbound.gone(version.get(), id));
			return;
		}
		bind(sub.session.getId(), id);
		log.info("WS resume: session={} party={}", sub.session.getId(), id);
		send(sub, Outbound.hosted(version.get(), party.get()));
	}

	/** Disband an ad this session owns (or proves it owns via key). */
	private void handleUnhost(Subscriber sub, Inbound in)
	{
		String id = in.id();
		if (id == null)
		{
			sendError(sub, null, "missing id");
			return;
		}
		if (!authorizeWrite(sub, id, in.key()))
		{
			return;
		}
		store.delete(id);
		unbind(sub.session.getId());
		log.info("WS unhost: session={} party={}", sub.session.getId(), id);
		// The reconciler broadcasts the removal on its next tick.
	}

	/**
	 * Authorise a host-only write: pass if this session already owns the ad, else if
	 * the supplied key matches (adopting ownership for subsequent writes). Sends the
	 * appropriate error frame and returns false otherwise.
	 */
	private boolean authorizeWrite(Subscriber sub, String id, String key)
	{
		if (id.equals(hostedBy.get(sub.session.getId())))
		{
			return true;
		}
		Authorization auth = store.authorize(id, key);
		if (auth == Authorization.NOT_FOUND)
		{
			sendError(sub, id, "gone");
			return false;
		}
		if (auth != Authorization.OK)
		{
			sendError(sub, id, "forbidden");
			return false;
		}
		bind(sub.session.getId(), id);
		return true;
	}

	private void bind(String sessionId, String partyId)
	{
		hostedBy.put(sessionId, partyId);
		String previous = ownerSession.put(partyId, sessionId);
		if (previous != null && !previous.equals(sessionId))
		{
			// A different session used to own this ad (e.g. the pre-reconnect one).
			hostedBy.remove(previous, partyId);
		}
	}

	private void unbind(String sessionId)
	{
		String partyId = hostedBy.remove(sessionId);
		if (partyId != null)
		{
			// Only clear the reverse map if it still points here (a newer resume may own it).
			ownerSession.remove(partyId, sessionId);
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
	{
		subscribers.remove(session.getId());
		// Don't delete the ad: liveness is the grace window. If the host doesn't
		// reconnect and resume before its TTL lapses, it's reaped normally.
		unbind(session.getId());
		log.info("WS closed: session={} status={} (subscribers={})",
			session.getId(), status, subscribers.size());
	}

	/**
	 * Socket-as-heartbeat: while a host's session is open, keep its ad's TTL fresh.
	 * A disconnected host stops being touched, so its ad lapses after the grace window.
	 */
	@Scheduled(fixedDelayString = "${app.ws.touch-interval-ms:5000}")
	public void touchOwnedParties()
	{
		for (Map.Entry<String, String> entry : hostedBy.entrySet())
		{
			Subscriber sub = subscribers.get(entry.getKey());
			if (sub == null || !sub.session.isOpen())
			{
				continue;
			}
			if (store.update(entry.getValue(), TTL_TOUCH).isEmpty())
			{
				unbind(entry.getKey()); // ad vanished out from under us
			}
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception)
	{
		subscribers.remove(session.getId());
		try
		{
			session.close(CloseStatus.SERVER_ERROR);
		}
		catch (IOException ignored)
		{
		}
	}

	private void sendSnapshot(Subscriber sub)
	{
		List<Party> list = store.list(sub.activity);
		log.debug("WS snapshot -> {} ({} parties)", sub.session.getId(), list.size());
		send(sub, Outbound.snapshot(version.get(), list));
	}

	private void sendError(Subscriber sub, String id, String detail)
	{
		send(sub, Outbound.error(version.get(), id, detail));
	}

	// --- Delta API, called by PartyReconciler (private parties never reach here:
	// the reconciler works off store.list(), which excludes them). ---

	void created(Party party)
	{
		fanOut(party.getActivity(), Outbound.created(version.incrementAndGet(), party));
	}

	void updated(Party party)
	{
		fanOut(party.getActivity(), Outbound.updated(version.incrementAndGet(), party));
	}

	void removed(String id, String activity)
	{
		fanOut(activity, Outbound.removed(version.incrementAndGet(), id));
	}

	private void fanOut(String activity, Outbound msg)
	{
		for (Subscriber sub : subscribers.values())
		{
			// Only connections that explicitly subscribed get the list firehose — a
			// host-only socket (no search view) connects but never subscribes.
			if (sub.subscribed && (sub.activity == null || sub.activity.equals(activity)))
			{
				send(sub, msg);
			}
		}
	}

	private void send(Subscriber sub, Outbound msg)
	{
		if (!sub.session.isOpen())
		{
			return;
		}
		String json;
		try
		{
			json = mapper.writeValueAsString(msg);
		}
		catch (Exception e)
		{
			log.warn("Failed to serialise {} frame", msg.type(), e);
			return;
		}
		// Set this logger to DEBUG to see the exact data pushed on each live connection.
		log.debug("WS -> {} {}", sub.session.getId(), json);
		try
		{
			sub.session.sendMessage(new TextMessage(json));
		}
		catch (Exception e)
		{
			log.debug("Dropping subscriber {} after send failure: {}", sub.session.getId(), e.toString());
			subscribers.remove(sub.session.getId());
			try
			{
				sub.session.close(CloseStatus.SERVER_ERROR);
			}
			catch (IOException ignored)
			{
			}
		}
	}

	/** The client's address — preferring {@code X-Forwarded-For} so it's the real
	 * client rather than the reverse proxy when one is in front. */
	private static String remoteOf(WebSocketSession session)
	{
		List<String> forwarded = session.getHandshakeHeaders().get("X-Forwarded-For");
		if (forwarded != null && !forwarded.isEmpty())
		{
			return forwarded.get(0);
		}
		return String.valueOf(session.getRemoteAddress());
	}

	private static final class Subscriber
	{
		final WebSocketSession session;
		/** Whether this connection has opted into the party-list firehose. */
		volatile boolean subscribed;
		/** The activity id this client wants, or null for every public party. */
		volatile String activity;

		Subscriber(WebSocketSession session)
		{
			this.session = session;
		}
	}

	/**
	 * Client -> server control frame. Only the fields relevant to {@code type} are
	 * set: {@code subscribe} uses {@code activity}; {@code host} uses {@code request}
	 * + {@code key}; {@code update}/{@code unhost} use {@code id} (+ {@code patch} /
	 * {@code key}); {@code resume} uses {@code id} + {@code key}.
	 */
	record Inbound(String type, String activity, PartyRequest request, PartyUpdate patch, String id, String key)
	{
	}

	/**
	 * Server -> client frame. {@code @JsonInclude(NON_NULL)} keeps each frame to the
	 * fields its type uses (snapshot carries {@code parties}; created/updated/hosted
	 * carry {@code party}; removed/gone carry {@code id}; error carries {@code detail})
	 * despite the global "always" default.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Outbound(String type, long version, List<Party> parties, Party party, String id, String detail)
	{
		static Outbound snapshot(long version, List<Party> parties)
		{
			return new Outbound("snapshot", version, parties, null, null, null);
		}

		static Outbound created(long version, Party party)
		{
			return new Outbound("created", version, null, party, null, null);
		}

		static Outbound updated(long version, Party party)
		{
			return new Outbound("updated", version, null, party, null, null);
		}

		static Outbound removed(long version, String id)
		{
			return new Outbound("removed", version, null, null, id, null);
		}

		/** Directed ack to a host: carries the full ad (server-assigned id/inviteCode). */
		static Outbound hosted(long version, Party party)
		{
			return new Outbound("hosted", version, null, party, null, null);
		}

		/** Directed: the ad the host tried to reclaim is gone (grace expired). */
		static Outbound gone(long version, String id)
		{
			return new Outbound("gone", version, null, null, id, null);
		}

		/** Directed: a write was rejected (e.g. not the owner). */
		static Outbound error(long version, String id, String detail)
		{
			return new Outbound("error", version, null, null, id, detail);
		}
	}
}

package net.osparty.api.web.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.osparty.api.repository.PartyRepository;
import net.osparty.api.repository.PartyRepository.Authorization;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyDelta;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
import net.osparty.api.service.DiscordLinkService;
import net.osparty.api.service.VoiceChannelService;
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
	private final net.osparty.api.service.VoiceChannelService voice;
	private final net.osparty.api.service.DiscordLinkService discordLinks;
	private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
	private final Map<String, String> hostedBy = new ConcurrentHashMap<>();
	private final Map<String, String> ownerSession = new ConcurrentHashMap<>();
	private final AtomicLong version = new AtomicLong();
	/** Last active-user count pushed to clients; {@code -1} until the first broadcast. */
	private volatile int lastPresence = -1;

	public PartyBroadcaster(PartyRepository store, ObjectMapper mapper,
		net.osparty.api.service.VoiceChannelService voice,
		net.osparty.api.service.DiscordLinkService discordLinks) {
		this.store = store;
		this.mapper = mapper;
		this.voice = voice;
		this.discordLinks = discordLinks;
	}

	/** Currently connected WebSocket clients, exported as a Micrometer gauge (see MetricsConfig). */
	public int activeConnections() {
		return subscribers.size();
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(
			session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
		Subscriber sub = new Subscriber(guarded);
		subscribers.put(session.getId(), sub);
		log.info("WS connected: session={} remote={} (subscribers={})",
			session.getId(), remoteOf(session), subscribers.size());
		// Give the newcomer the current active-user count right away; everyone else learns
		// of the change via the throttled scheduled broadcast below.
		send(sub, Outbound.presence(version.get(), subscribers.size()));
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
			case "createVoiceChannel":
				handleCreateVoiceChannel(sub, in);
				break;
			case "startDiscordLink":
				handleStartDiscordLink(sub, in);
				break;
			case "getDiscordLink":
				handleGetDiscordLink(sub, in);
				break;
			case "unlinkDiscord":
				handleUnlinkDiscord(sub, in);
				break;
			case "kickVoiceMember":
				handleKickVoiceMember(sub, in);
				break;
			case "requestVoiceAccess":
				handleRequestVoiceAccess(sub, in);
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
		// Delete here (not only via the reconciler) because the reconciler's list() excludes private
		// parties, so a private party's channel would otherwise never be cleaned up on disband. The
		// reconciler still covers the public TTL/crash path; a double delete is a harmless no-op.
		Party deleted = store.delete(id).orElse(null);
		if (deleted != null && deleted.getDiscordChannelId() != null) {
			voice.delete(deleted.getDiscordChannelId());
		}
		unbind(sub.session.getId());
		log.info("WS unhost: session={} party={}", sub.session.getId(), id);
	}

	/**
	 * Host action: provision a Discord voice channel for the hosted party and return its invite URL.
	 * Idempotent — if a channel already exists we just echo its URL back, so a double-click (or a
	 * resume after reconnect) never creates a second channel. The host then re-broadcasts the URL to
	 * its actual party members over the RuneLite peer bus; the ad firehose never carries it.
	 */
	private void handleCreateVoiceChannel(Subscriber sub, Inbound in) {
		String id = in.id();
		if (id == null) {
			sendError(sub, null, "missing id");
			return;
		}
		if (!authorizeWrite(sub, id, in.key())) {
			return;
		}
		Party party = store.findById(id).orElse(null);
		if (party == null) {
			sendError(sub, id, "gone");
			unbind(sub.session.getId());
			return;
		}
		if (party.getDiscordInviteUrl() != null) {
			send(sub, Outbound.voiceChannel(version.get(), id, party.getDiscordInviteUrl()));
			return;
		}
		Optional<VoiceChannelService.VoiceChannelInfo> channel =
			voice.createForParty(party, linkedDiscordIds(party));
		if (channel.isEmpty()) {
			sendError(sub, id, "voice unavailable");
			return;
		}
		// Store + broadcast the invite URL as the "Join voice" link. Its "Open Discord" page launches the
		// desktop app (the approach that worked in the first iteration).
		store.attachVoiceChannel(id, channel.get().channelId(), channel.get().inviteUrl());
		log.info("WS voice channel: session={} party={} channel={}", sub.session.getId(), id,
			channel.get().channelId());
		send(sub, Outbound.voiceChannel(version.get(), id, channel.get().inviteUrl()));
	}

	/**
	 * Start an OAuth2 Discord link for the caller's accountHash: mint a nonce and return the authorize
	 * URL for the plugin to open in a browser. No host authorization — linking is per-user and only
	 * binds the accountHash the caller supplies (same cooperative-trust model as the rest of the API).
	 */
	private void handleStartDiscordLink(Subscriber sub, Inbound in) {
		if (!discordLinks.isEnabled()) {
			sendError(sub, null, "linking disabled");
			return;
		}
		if (in.accountHash() == null || in.accountHash() == 0) {
			sendError(sub, null, "missing accountHash");
			return;
		}
		String url = discordLinks.beginLink(in.accountHash());
		send(sub, Outbound.discordLinkUrl(version.get(), url));
	}

	/** Remove the caller's Discord binding (both directions). Reply with an unlinked status for confirmation. */
	private void handleUnlinkDiscord(Subscriber sub, Inbound in) {
		if (in.accountHash() == null || in.accountHash() == 0) {
			sendError(sub, null, "missing accountHash");
			return;
		}
		discordLinks.unlink(in.accountHash());
		log.info("Unlinked Discord for accountHash {}", in.accountHash());
		send(sub, Outbound.discordLink(version.get(), in.accountHash(), null, null));
	}

	/** Report whether an accountHash is linked, echoing the hash so the poller can match the reply. */
	private void handleGetDiscordLink(Subscriber sub, Inbound in) {
		if (in.accountHash() == null || in.accountHash() == 0) {
			sendError(sub, null, "missing accountHash");
			return;
		}
		DiscordLinkService.Link link = discordLinks.getByAccountHash(in.accountHash()).orElse(null);
		send(sub, Outbound.discordLink(version.get(), in.accountHash(),
			link == null ? null : link.discordId(), link == null ? null : link.username()));
	}

	/** The linked Discord ids of a party's current members, for per-user channel access. */
	private List<String> linkedDiscordIds(Party party) {
		List<String> ids = new java.util.ArrayList<>();
		if (party.getMembers() != null) {
			for (net.osparty.api.model.Member member : party.getMembers()) {
				if (member.getAccountHash() != 0) {
					discordLinks.discordIdForAccountHash(member.getAccountHash()).ifPresent(ids::add);
				}
			}
		}
		return ids;
	}

	/**
	 * Member self-service: grant the caller per-user access to the party's voice channel before they open
	 * the invite, so someone who joined or linked after the channel was created can still get in. Verified
	 * by the caller's accountHash being in the party roster (cooperative trust) and being Discord-linked.
	 */
	private void handleRequestVoiceAccess(Subscriber sub, Inbound in) {
		String id = in.id();
		if (id == null) {
			sendError(sub, null, "missing id");
			return;
		}
		if (in.accountHash() == null || in.accountHash() == 0) {
			sendError(sub, id, "missing accountHash");
			return;
		}
		Party party = store.findById(id).orElse(null);
		if (party == null || party.getDiscordChannelId() == null) {
			sendError(sub, id, "no channel");
			return;
		}
		boolean inParty = party.getMembers() != null && party.getMembers().stream()
			.anyMatch(m -> m.getAccountHash() == in.accountHash());
		if (!inParty) {
			sendError(sub, id, "not in party");
			return;
		}
		String discordId = discordLinks.discordIdForAccountHash(in.accountHash()).orElse(null);
		if (discordId == null) {
			sendError(sub, id, "not linked");
			return;
		}
		// Synchronous: only tell the client "you're in" once the override is actually live, so the plugin
		// doesn't open the invite to a still-invisible channel (which forced the old see-nothing-then-retry).
		if (!voice.grantAccess(party.getDiscordChannelId(), discordId)) {
			sendError(sub, id, "voice access failed");
			return;
		}
		send(sub, Outbound.voiceAccess(version.get(), id));
	}

	/**
	 * Host action: disconnect a kicked member from the party's voice channel. Requires host auth; no-ops
	 * unless the party has a channel and the member's accountHash is linked to a Discord user. The bot
	 * itself only disconnects them if they're actually sitting in that channel. Fire-and-forget.
	 */
	private void handleKickVoiceMember(Subscriber sub, Inbound in) {
		String id = in.id();
		if (id == null) {
			sendError(sub, null, "missing id");
			return;
		}
		if (!authorizeWrite(sub, id, in.key())) {
			return;
		}
		if (in.accountHash() == null || in.accountHash() == 0) {
			return;
		}
		Party party = store.findById(id).orElse(null);
		if (party == null || party.getDiscordChannelId() == null) {
			log.info("kickVoiceMember party={} accountHash={}: no channel, skipping", id, in.accountHash());
			return; // no channel to remove them from
		}
		String discordId = discordLinks.discordIdForAccountHash(in.accountHash()).orElse(null);
		if (discordId == null) {
			log.info("kickVoiceMember party={} accountHash={}: member not Discord-linked, skipping",
				id, in.accountHash());
			return;
		}
		log.info("kickVoiceMember party={} accountHash={} -> revoking + disconnecting Discord user {} from channel {}",
			id, in.accountHash(), discordId, party.getDiscordChannelId());
		// Revoke their per-user view access (so the channel disappears for them) and disconnect them if
		// they're currently in it.
		voice.revokeAccess(party.getDiscordChannelId(), discordId);
		voice.disconnectFromChannel(party.getDiscordChannelId(), discordId);
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

	/**
	 * Push the current active-user count to every connected client, but only when it has
	 * changed since the last push. Throttling to a scheduled tick (rather than firing on
	 * every connect/disconnect) keeps this O(subscribers) per interval instead of
	 * O(subscribers) per churn event, which matters under heavy connection turnover.
	 */
	@Scheduled(fixedDelayString = "${app.ws.presence-interval-ms:5000}")
	public void broadcastPresence() {
		int online = subscribers.size();
		if (online == lastPresence) {
			return;
		}
		lastPresence = online;
		Outbound frame = Outbound.presence(version.get(), online);
		for (Subscriber sub : subscribers.values()) {
			if (sub.session.isOpen()) {
				send(sub, frame);
			}
		}
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

	/**
	 * Push a whole tick's worth of changes as ONE frame per subscriber instead of
	 * one frame per changed party per subscriber. Cuts the per-tick message count
	 * from O(changed x subscribers) to O(subscribers), and serialises at most once
	 * per distinct activity filter rather than once per send.
	 */
	void broadcastBatch(List<Party> created, List<PartyDelta> updated, List<RemovedRef> removed) {
		if (created.isEmpty() && updated.isEmpty() && removed.isEmpty()) {
			return;
		}
		long v = version.incrementAndGet();
		Map<String, TextMessage> perActivity = new java.util.HashMap<>();
		for (Subscriber sub : subscribers.values()) {
			if (!sub.subscribed || !sub.session.isOpen()) {
				continue;
			}
			// One serialised frame per distinct activity scope. HashMap permits the null ("all
			// activities") key, so no sentinel string is needed; a cached null means "nothing
			// matches this scope" and is neither rebuilt nor sent.
			if (!perActivity.containsKey(sub.activity)) {
				perActivity.put(sub.activity, buildBatch(v, sub.activity, created, updated, removed));
			}
			TextMessage frame = perActivity.get(sub.activity);
			if (frame != null) {
				sendRaw(sub, frame);
			}
		}
	}

	private TextMessage buildBatch(long v, String activity, List<Party> created, List<PartyDelta> updated,
		List<RemovedRef> removed) {
		List<Party> c = filterCreated(created, activity);
		List<PartyDelta> u = filterUpdated(updated, activity);
		List<String> r = new java.util.ArrayList<>();
		for (RemovedRef ref : removed) {
			if (activity == null || activity.equals(ref.activity())) {
				r.add(ref.id());
			}
		}
		if (c.isEmpty() && u.isEmpty() && r.isEmpty()) {
			return null;
		}
		Batch batch = new Batch("batch", v, c.isEmpty() ? null : c, u.isEmpty() ? null : u, r.isEmpty() ? null : r);
		try {
			return new TextMessage(mapper.writeValueAsString(batch));
		}
		catch (Exception e) {
			log.warn("Failed to serialise batch frame", e);
			return null;
		}
	}

	private static List<Party> filterCreated(List<Party> parties, String activity) {
		if (activity == null) {
			return parties;
		}
		List<Party> out = new java.util.ArrayList<>();
		for (Party p : parties) {
			if (activity.equals(p.getActivity())) {
				out.add(p);
			}
		}
		return out;
	}

	private static List<PartyDelta> filterUpdated(List<PartyDelta> deltas, String activity) {
		if (activity == null) {
			return deltas;
		}
		List<PartyDelta> out = new java.util.ArrayList<>();
		for (PartyDelta d : deltas) {
			if (activity.equals(d.activity())) {
				out.add(d);
			}
		}
		return out;
	}

	private void sendRaw(Subscriber sub, TextMessage frame) {
		if (!sub.session.isOpen()) {
			return;
		}
		try {
			sub.session.sendMessage(frame);
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

	/** Identifies a removed ad plus the activity it had, so removals can be activity-filtered. */
	record RemovedRef(String id, String activity) {
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
		String code, String host, Long accountHash) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Outbound(String type, long version, List<Party> parties, Party party, String id, String detail,
		Integer online, String url, String username, Long accountHash) {
		static Outbound snapshot(long version, List<Party> parties) {
			return new Outbound("snapshot", version, parties, null, null, null, null, null, null, null);
		}

		static Outbound hosted(long version, Party party) {
			return new Outbound("hosted", version, null, party, null, null, null, null, null, null);
		}

		static Outbound gone(long version, String id) {
			return new Outbound("gone", version, null, null, id, null, null, null, null, null);
		}

		static Outbound error(long version, String id, String detail) {
			return new Outbound("error", version, null, null, id, detail, null, null, null, null);
		}

		static Outbound byCode(long version, String code, Party party) {
			return new Outbound("byCode", version, null, party, code, null, null, null, null, null);
		}

		static Outbound byHost(long version, String host, Party party) {
			return new Outbound("byHost", version, null, party, host, null, null, null, null, null);
		}

		/** Global count of connected plugin clients ("active users"). */
		static Outbound presence(long version, int online) {
			return new Outbound("presence", version, null, null, null, null, online, null, null, null);
		}

		/** Reply to createVoiceChannel: the party id and the Discord invite URL to share with members. */
		static Outbound voiceChannel(long version, String id, String url) {
			return new Outbound("voiceChannel", version, null, null, id, null, null, url, null, null);
		}

		/** Reply to startDiscordLink: the Discord OAuth authorize URL to open in a browser. */
		static Outbound discordLinkUrl(long version, String url) {
			return new Outbound("discordLinkUrl", version, null, null, null, null, null, url, null, null);
		}

		/** Reply to getDiscordLink: the linked Discord id + username (both null when not linked). */
		static Outbound discordLink(long version, long accountHash, String discordId, String username) {
			return new Outbound("discordLink", version, null, null, discordId, null, null, null, username,
				accountHash);
		}

		/** Ack to requestVoiceAccess: the caller has been granted access; the plugin may open the invite. */
		static Outbound voiceAccess(long version, String id) {
			return new Outbound("voiceAccess", version, null, null, id, null, null, null, null, null);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Batch(String type, long version, List<Party> created, List<PartyDelta> updated, List<String> removed) {
	}
}

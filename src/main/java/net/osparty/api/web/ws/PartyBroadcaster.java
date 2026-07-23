package net.osparty.api.web.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
	private final net.osparty.api.service.DiscordBadgeService badges;
	private final PresenceRegistry presence;
	private final InviteBus inviteBus;
	private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
	private final Map<String, String> hostedBy = new ConcurrentHashMap<>();
	private final Map<String, String> ownerSession = new ConcurrentHashMap<>();
	// Self-asserted identity indexes so an invite can be routed to a specific online client.
	private final Map<Long, String> sessionByAccount = new ConcurrentHashMap<>();
	private final Map<String, String> sessionByName = new ConcurrentHashMap<>();
	private final AtomicLong version = new AtomicLong();
	private volatile int lastPresence = -1;
	private final Counter partiesCreated;

	public PartyBroadcaster(PartyRepository store, ObjectMapper mapper,
		net.osparty.api.service.VoiceChannelService voice,
		net.osparty.api.service.DiscordLinkService discordLinks,
		net.osparty.api.service.DiscordBadgeService badges,
		PresenceRegistry presence,
		InviteBus inviteBus,
		MeterRegistry meterRegistry) {
		this.store = store;
		this.mapper = mapper;
		this.voice = voice;
		this.discordLinks = discordLinks;
		this.badges = badges;
		this.presence = presence;
		this.inviteBus = inviteBus;
		// Cross-node invite delivery calls back here to reach a target connected to this instance.
		inviteBus.setLocalDelivery(this::deliverInviteLocally);
		this.partiesCreated = Counter.builder("parties.created")
				.description("Number of parties created")
				.register(meterRegistry);
	}

	public int activeConnections() {
		return subscribers.size();
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(
			session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
		Subscriber sub = new Subscriber(guarded);
		subscribers.put(session.getId(), sub);
		log.info("WS connected: session={} (subscribers={})",
			session.getId(), subscribers.size());
		send(sub, Outbound.presence(version.get(), lastPresence >= 0 ? lastPresence : subscribers.size()));
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
			case "transferHost":
				handleTransferHost(sub, in);
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
			case "setBadgeVisibility":
				handleSetBadgeVisibility(sub, in);
				break;
			case "kickVoiceMember":
				handleKickVoiceMember(sub, in);
				break;
			case "requestVoiceAccess":
				handleRequestVoiceAccess(sub, in);
				break;
			case "identify":
				handleIdentify(sub, in);
				break;
			case "invite":
				handleInvite(sub, in);
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
		send(sub, Outbound.byCode(version.get(), code, enriched(party)));
	}

	private void handleGetByHost(Subscriber sub, Inbound in) {
		String host = in.host();
		Party party = host == null ? null : store.findByHost(host).orElse(null);
		send(sub, Outbound.byHost(version.get(), host, enriched(party)));
	}

	private void handleHost(Subscriber sub, Inbound in) {
		if (in.request() == null) {
			sendError(sub, null, "missing request");
			return;
		}
		Party party = store.create(in.request(), in.key());
		bind(sub.session.getId(), party.getId());
		log.info("WS host: session={} party={} host={}", sub.session.getId(), party.getId(), party.getHost());
		partiesCreated.increment();
		send(sub, Outbound.hosted(version.get(), enriched(party)));
	}

	private Party enriched(Party party) {
		return party == null ? null : badges.enrichParties(List.of(party)).get(0);
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
		send(sub, Outbound.hosted(version.get(), enriched(party.get())));
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
		Party deleted = store.delete(id).orElse(null);
		if (deleted != null && deleted.getDiscordChannelId() != null) {
			voice.delete(deleted.getDiscordChannelId());
		}
		unbind(sub.session.getId());
		log.info("WS unhost: session={} party={}", sub.session.getId(), id);
		partiesCreated.increment(-1);
	}

	private void handleTransferHost(Subscriber sub, Inbound in) {
		String id = in.id();
		if (id == null) {
			sendError(sub, null, "missing id");
			return;
		}
		if (in.host() == null || in.host().isBlank()) {
			sendError(sub, id, "missing host");
			return;
		}
		if (in.newKey() == null || in.newKey().isBlank()) {
			sendError(sub, id, "missing newKey");
			return;
		}
		if (!authorizeWrite(sub, id, in.key())) {
			return;
		}
		Optional<Party> party = store.transferHost(id, in.host(), in.newKey());
		if (party.isEmpty()) {
			sendError(sub, id, "gone");
			unbind(sub.session.getId());
			return;
		}
		unbind(sub.session.getId());
		// The Discord channel name embeds the host, so rename it to match the new host (best-effort).
		if (party.get().getDiscordChannelId() != null) {
			voice.rename(party.get().getDiscordChannelId(), party.get());
		}
		log.info("WS transferHost: session={} party={} newHost={}", sub.session.getId(), id, in.host());
		send(sub, Outbound.transferred(version.get(), id));
	}

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
		store.attachVoiceChannel(id, channel.get().channelId(), channel.get().inviteUrl());
		log.info("WS voice channel: session={} party={} channel={}", sub.session.getId(), id,
			channel.get().channelId());
		send(sub, Outbound.voiceChannel(version.get(), id, channel.get().inviteUrl()));
	}

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

	private void handleUnlinkDiscord(Subscriber sub, Inbound in) {
		if (in.accountHash() == null || in.accountHash() == 0) {
			sendError(sub, null, "missing accountHash");
			return;
		}
		discordLinks.unlink(in.accountHash());
		log.info("Unlinked Discord for accountHash {}", in.accountHash());
		send(sub, Outbound.discordLink(version.get(), in.accountHash(), null, null, null));
	}

	private void handleGetDiscordLink(Subscriber sub, Inbound in) {
		if (in.accountHash() == null || in.accountHash() == 0) {
			sendError(sub, null, "missing accountHash");
			return;
		}
		DiscordLinkService.Link link = discordLinks.getByAccountHash(in.accountHash()).orElse(null);
		send(sub, Outbound.discordLink(version.get(), in.accountHash(),
			link == null ? null : link.discordId(), link == null ? null : link.username(),
			!badges.isBadgesHidden(in.accountHash())));
	}

	private void handleSetBadgeVisibility(Subscriber sub, Inbound in) {
		if (in.accountHash() == null || in.accountHash() == 0) {
			sendError(sub, null, "missing accountHash");
			return;
		}
		if (in.visible() == null) {
			sendError(sub, null, "missing visible");
			return;
		}
		badges.setBadgesHidden(in.accountHash(), !in.visible());
		log.info("Badge visibility for accountHash {} -> {}", in.accountHash(), in.visible());
		DiscordLinkService.Link link = discordLinks.getByAccountHash(in.accountHash()).orElse(null);
		send(sub, Outbound.discordLink(version.get(), in.accountHash(),
			link == null ? null : link.discordId(), link == null ? null : link.username(), in.visible()));
	}

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
		if (!voice.grantAccess(party.getDiscordChannelId(), discordId)) {
			sendError(sub, id, "voice access failed");
			return;
		}
		send(sub, Outbound.voiceAccess(version.get(), id));
	}

	/**
	 * Register this connection's self-reported OSRS identity so invites can be routed to it. Identity is
	 * self-asserted (consistent with the rest of the socket) and re-sent by the client on every reconnect.
	 */
	private void handleIdentify(Subscriber sub, Inbound in) {
		String sessionId = sub.session.getId();
		if (in.accountHash() != null && in.accountHash() != 0) {
			if (sub.accountHash != null && !sub.accountHash.equals(in.accountHash())) {
				sessionByAccount.remove(sub.accountHash, sessionId);
			}
			sub.accountHash = in.accountHash();
			sessionByAccount.put(in.accountHash(), sessionId);
		}
		String name = normalizeName(in.name());
		if (name != null) {
			if (sub.name != null && !sub.name.equals(name)) {
				sessionByName.remove(sub.name, sessionId);
			}
			sub.name = name;
			sessionByName.put(name, sessionId);
		}
	}

	/**
	 * Route a party invite from a member/host to a specific online friend by name. The sender must be in
	 * the party, the invitee must not already be, and the invitee must be connected. The sender gets an
	 * {@code inviteAck} reporting whether it was delivered; the invitee gets an {@code invited} push.
	 */
	private void handleInvite(Subscriber sub, Inbound in) {
		String id = in.id();
		if (id == null) {
			sendError(sub, null, "missing id");
			return;
		}
		String target = normalizeName(in.target());
		if (target == null) {
			sendError(sub, id, "missing target");
			return;
		}
		Party party = store.findById(id).orElse(null);
		if (party == null) {
			// The party vanished (TTL/disband) between opening the menu and inviting; report as not delivered.
			send(sub, Outbound.inviteAck(version.get(), in.target(), false));
			return;
		}
		if (!senderInParty(party, in)) {
			sendError(sub, id, "not in party");
			return;
		}
		if (memberByName(party, target) != null) {
			// They joined between the menu opening and the invite; nothing to deliver.
			send(sub, Outbound.inviteAck(version.get(), in.target(), false));
			return;
		}
		String from = (in.name() == null || in.name().isBlank()) ? party.getHost() : in.name();
		String frame;
		try {
			frame = mapper.writeValueAsString(Outbound.invited(version.get(), enriched(party), from));
		}
		catch (Exception e) {
			sendError(sub, id, "invite failed");
			return;
		}
		// The target may be connected to any replica; the bus finds and delivers it cluster-wide.
		inviteBus.dispatch(target, frame).whenComplete((delivered, error) -> {
			log.info("WS invite: session={} party={} target={} delivered={}",
				sub.session.getId(), id, target, delivered);
			send(sub, Outbound.inviteAck(version.get(), in.target(), delivered != null && delivered));
		});
	}

	/** Deliver a pre-serialised {@code invited} frame to {@code normalizedName} if connected to this node. */
	private boolean deliverInviteLocally(String normalizedName, String frameJson) {
		String sessionId = sessionByName.get(normalizedName);
		Subscriber sub = sessionId == null ? null : subscribers.get(sessionId);
		if (sub == null || !sub.session.isOpen()) {
			return false;
		}
		sendRaw(sub, new TextMessage(frameJson));
		return true;
	}

	/** Whether the invite sender is the party host or an admitted member (by name or accountHash). */
	private static boolean senderInParty(Party party, Inbound in) {
		String senderName = normalizeName(in.name());
		if (senderName != null && senderName.equals(normalizeName(party.getHost()))) {
			return true;
		}
		if (party.getMembers() == null) {
			return false;
		}
		if (in.accountHash() != null && in.accountHash() != 0) {
			long hash = in.accountHash();
			if (party.getMembers().stream().anyMatch(m -> m.getAccountHash() == hash)) {
				return true;
			}
		}
		return senderName != null && memberByName(party, senderName) != null;
	}

	private static net.osparty.api.model.Member memberByName(Party party, String normalizedName) {
		if (party.getMembers() == null) {
			return null;
		}
		return party.getMembers().stream()
			.filter(m -> normalizedName.equals(normalizeName(m.getName())))
			.findFirst().orElse(null);
	}

	/** Normalise an OSRS name for identity matching: strip the nbsp Jagex uses for spaces, trim, lowercase. */
	private static String normalizeName(String name) {
		if (name == null) {
			return null;
		}
		String normalized = name.replace('\u00A0', ' ').trim().toLowerCase();
		return normalized.isEmpty() ? null : normalized;
	}

	private void forgetIdentity(Subscriber sub) {
		if (sub == null) {
			return;
		}
		if (sub.accountHash != null) {
			sessionByAccount.remove(sub.accountHash, sub.session.getId());
		}
		if (sub.name != null) {
			sessionByName.remove(sub.name, sub.session.getId());
		}
	}

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
			return;
		}
		String discordId = discordLinks.discordIdForAccountHash(in.accountHash()).orElse(null);
		if (discordId == null) {
			log.info("kickVoiceMember party={} accountHash={}: member not Discord-linked, skipping",
				id, in.accountHash());
			return;
		}
		log.info("kickVoiceMember party={} accountHash={} -> revoking + disconnecting Discord user {} from channel {}",
			id, in.accountHash(), discordId, party.getDiscordChannelId());
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
		forgetIdentity(subscribers.remove(session.getId()));
		unbind(session.getId());
		log.info("WS closed: session={} status={} (subscribers={})",
			session.getId(), status, subscribers.size());
	}

	@Scheduled(fixedDelayString = "${app.ws.presence-interval-ms:5000}")
	public void broadcastPresence() {
		int online = presence.record(subscribers.size());
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
				log.info("WS touch: party {} is gone; notifying host session {}",
					entry.getValue(), entry.getKey());
				send(sub, Outbound.gone(version.get(), entry.getValue()));
				unbind(entry.getKey());
			}
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		forgetIdentity(subscribers.remove(session.getId()));
		try {
			session.close(CloseStatus.SERVER_ERROR);
		}
		catch (IOException ignored) {
		}
	}

	private void sendSnapshot(Subscriber sub) {
		List<Party> list = badges.enrichParties(store.list(sub.activity));
		log.debug("WS snapshot -> {} ({} parties)", sub.session.getId(), list.size());
		send(sub, Outbound.snapshot(version.get(), list));
	}

	private void sendError(Subscriber sub, String id, String detail) {
		send(sub, Outbound.error(version.get(), id, detail));
	}

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

	private static final class Subscriber {
		final WebSocketSession session;
		volatile boolean subscribed;
		volatile String activity;
		// Self-reported identity, mirrored into sessionByAccount/sessionByName for invite routing.
		volatile Long accountHash;
		volatile String name;

		Subscriber(WebSocketSession session) {
			this.session = session;
		}
	}

	record Inbound(String type, String activity, PartyRequest request, PartyUpdate patch, String id, String key,
		String code, String host, Long accountHash, Boolean visible, String newKey, String name, String target) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Outbound(String type, long version, List<Party> parties, Party party, String id, String detail,
		Integer online, String url, String username, Long accountHash, Boolean badgesVisible, String from,
		Boolean delivered) {
		static Outbound snapshot(long version, List<Party> parties) {
			return new Outbound("snapshot", version, parties, null, null, null, null, null, null, null, null, null, null);
		}

		static Outbound hosted(long version, Party party) {
			return new Outbound("hosted", version, null, party, null, null, null, null, null, null, null, null, null);
		}

		static Outbound gone(long version, String id) {
			return new Outbound("gone", version, null, null, id, null, null, null, null, null, null, null, null);
		}

		static Outbound error(long version, String id, String detail) {
			return new Outbound("error", version, null, null, id, detail, null, null, null, null, null, null, null);
		}

		static Outbound byCode(long version, String code, Party party) {
			return new Outbound("byCode", version, null, party, code, null, null, null, null, null, null, null, null);
		}

		static Outbound byHost(long version, String host, Party party) {
			return new Outbound("byHost", version, null, party, host, null, null, null, null, null, null, null, null);
		}

		static Outbound presence(long version, int online) {
			return new Outbound("presence", version, null, null, null, null, online, null, null, null, null, null, null);
		}

		static Outbound voiceChannel(long version, String id, String url) {
			return new Outbound("voiceChannel", version, null, null, id, null, null, url, null, null, null, null, null);
		}

		static Outbound discordLinkUrl(long version, String url) {
			return new Outbound("discordLinkUrl", version, null, null, null, null, null, url, null, null, null, null, null);
		}

		static Outbound discordLink(long version, long accountHash, String discordId, String username,
			Boolean badgesVisible) {
			return new Outbound("discordLink", version, null, null, discordId, null, null, null, username,
				accountHash, badgesVisible, null, null);
		}

		static Outbound voiceAccess(long version, String id) {
			return new Outbound("voiceAccess", version, null, null, id, null, null, null, null, null, null, null, null);
		}

		static Outbound transferred(long version, String id) {
			return new Outbound("transferred", version, null, null, id, null, null, null, null, null, null, null, null);
		}

		static Outbound invited(long version, Party party, String from) {
			return new Outbound("invited", version, null, party, null, null, null, null, null, null, null, from, null);
		}

		static Outbound inviteAck(long version, String target, boolean delivered) {
			return new Outbound("inviteAck", version, null, null, target, null, null, null, null, null, null, null,
				delivered);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Batch(String type, long version, List<Party> created, List<PartyDelta> updated, List<String> removed) {
	}
}

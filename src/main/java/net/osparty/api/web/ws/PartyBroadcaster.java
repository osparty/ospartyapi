package net.osparty.api.web.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.osparty.api.repository.PartyRepository;
import net.osparty.api.repository.PartyRepository.Authorization;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyDelta;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
import net.osparty.api.service.DiscordLinkService;
import net.osparty.api.service.PartyFactory;
import net.osparty.api.service.ReportRateLimiter;
import net.osparty.api.service.VoiceChannelService;
import net.osparty.api.web.config.ClientAddressHandshakeInterceptor;
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
import org.springframework.web.socket.BinaryMessage;
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
	private final net.osparty.api.service.BanService bans;
	/** Announces an ad's change to every node, so nobody has to re-scan the board to find it. */
	private final PartyChangeBus changes;
	/** Kill switches: both lookups are reachable by the banned host's own client. */
	private final boolean filterByHost;
	private final boolean filterByCode;
	private final net.osparty.api.repository.AdReportRepository reports;
	private final net.osparty.api.service.AdReportService adReports;
	private final net.osparty.api.service.ReportRateLimiter rateLimiter;
	private final MeterRegistry meterRegistry;
	private final boolean reportsEnabled;
	private final int reportsPerSession;
	private final io.micrometer.core.instrument.Counter reportsReceived;
	private final io.micrometer.core.instrument.Counter reportsNotified;
	private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
	private final Map<String, String> hostedBy = new ConcurrentHashMap<>();
	private final Map<String, String> ownerSession = new ConcurrentHashMap<>();
	// Self-asserted identity indexes so an invite can be routed to a specific online client.
	private final Map<Long, String> sessionByAccount = new ConcurrentHashMap<>();
	private final Map<String, String> sessionByName = new ConcurrentHashMap<>();
	private final AtomicLong version = new AtomicLong();
	private volatile int lastPresence = -1;
	/** The board as of the last reconcile, shared by every subscriber that joins before the next one. */
	private volatile Board board;

	public PartyBroadcaster(PartyRepository store, ObjectMapper mapper,
		net.osparty.api.service.VoiceChannelService voice,
		net.osparty.api.service.DiscordLinkService discordLinks,
		net.osparty.api.service.DiscordBadgeService badges,
		PresenceRegistry presence,
		InviteBus inviteBus,
		net.osparty.api.service.BanService bans,
		PartyChangeBus changes,
		@org.springframework.beans.factory.annotation.Value("${app.bans.filter-get-by-host:true}")
		boolean filterByHost,
		@org.springframework.beans.factory.annotation.Value("${app.bans.filter-get-by-code:true}")
		boolean filterByCode,
		net.osparty.api.repository.AdReportRepository reports,
		net.osparty.api.service.AdReportService adReports,
		net.osparty.api.service.ReportRateLimiter rateLimiter,
		@org.springframework.beans.factory.annotation.Value("${app.reports.enabled:true}")
		boolean reportsEnabled,
		@org.springframework.beans.factory.annotation.Value("${app.reports.per-session-max:5}")
		int reportsPerSession,
		MeterRegistry meterRegistry) {
		this.store = store;
		this.mapper = mapper;
		this.voice = voice;
		this.discordLinks = discordLinks;
		this.badges = badges;
		this.presence = presence;
		this.inviteBus = inviteBus;
		this.bans = bans;
		this.changes = changes;
		this.filterByHost = filterByHost;
		this.filterByCode = filterByCode;
		this.reports = reports;
		this.adReports = adReports;
		this.rateLimiter = rateLimiter;
		this.meterRegistry = meterRegistry;
		this.reportsEnabled = reportsEnabled;
		this.reportsPerSession = reportsPerSession;
		this.reportsReceived = io.micrometer.core.instrument.Counter
			.builder("osparty.reports.received")
			.description("Advertisement reports accepted from clients")
			.register(meterRegistry);
		this.reportsNotified = io.micrometer.core.instrument.Counter
			.builder("osparty.reports.notified")
			.description("Advertisement reports that produced a Discord review message")
			.register(meterRegistry);
		// Cross-node invite delivery calls back here to reach a target connected to this instance.
		inviteBus.setLocalDelivery(this::deliverInviteLocally);
		Gauge.builder("parties.active", store, PartyRepository::partyCount)
				.description("Current number of active parties")
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
			case "report":
				handleReport(sub, in);
				break;
			default:
				break;
		}
	}

	private void handleSubscribe(Subscriber sub, Inbound in) {
		sub.activity = (in.activity() == null || in.activity().isBlank()) ? null : in.activity();
		// Opt-in, and read before the snapshot goes out so the first frame is already compressed. A client
		// that says nothing keeps getting text, which is what every version before this one expects.
		sub.compressed = Boolean.TRUE.equals(in.compress());
		sub.subscribed = true;
		log.info("WS subscribe: session={} activity={}", sub.session.getId(),
			sub.activity == null ? "<all>" : sub.activity);
		sendSnapshot(sub, in.since());
	}

	private void handleUnsubscribe(Subscriber sub) {
		sub.subscribed = false;
	}

	private void handleGetByCode(Subscriber sub, Inbound in) {
		String code = in.code();
		Party party = code == null ? null : store.findByInviteCode(code).orElse(null);
		if (filterByCode && hiddenFromViewer(sub, party)) {
			party = null;
		}
		send(sub, Outbound.byCode(version.get(), code, enriched(party)));
	}

	private void handleGetByHost(Subscriber sub, Inbound in) {
		String host = in.host();
		Party party = host == null ? null : store.findByHost(host).orElse(null);
		if (filterByHost && hiddenFromViewer(sub, party)) {
			party = null;
		}
		send(sub, Outbound.byHost(version.get(), host, enriched(party)));
	}

	/** Shadowbanned, and not this viewer's own advertisement. See {@link #isOwnParty}. */
	private boolean hiddenFromViewer(Subscriber sub, Party party) {
		return party != null && bans.isHidden(party) && !isOwnParty(sub, party);
	}

	private void handleHost(Subscriber sub, Inbound in) {
		if (in.request() == null) {
			sendError(sub, null, "missing request");
			return;
		}
		Party party = store.create(in.request(), in.key());
		bind(sub.session.getId(), party.getId());
		log.info("WS host: session={} party={} host={}", sub.session.getId(), party.getId(), party.getHost());
		send(sub, Outbound.hosted(version.get(), enriched(party)));
		// After the host's own ack, always: the announcement puts the ad on everyone's board, and a host
		// should learn its advertisement exists before the rest of the world does.
		changes.publish(party.getId(), party.getSeq());
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
		Optional<Party> updated = store.update(id, in.patch() == null ? TTL_TOUCH : in.patch());
		if (updated.isEmpty()) {
			sendError(sub, id, "gone");
			unbind(sub.session.getId());
			return;
		}
		// Only a real edit is announced. A bare heartbeat changes nothing anyone can see, and at one per
		// hosted ad per interval it would put more traffic on the bus than the sweep it exists to replace.
		if (in.patch() != null) {
			changes.publish(id, updated.get().getSeq());
		}
		// Only a real edit is announced. A bare heartbeat changes nothing anyone can see, and at one per
		// hosted ad per interval it would put more traffic on the bus than the sweep it exists to replace.
		if (in.patch() != null) {
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
		// A removal has no advertisement left to stamp, so it takes the next revision from the same
		// sequence — which is what lets every node order it against everything else.
		changes.publish(id, store.nextRevision());
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
		changes.publish(id, party.get().getSeq());
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
		changes.publish(id, store.findById(id).map(Party::getSeq).orElse(0L));
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
		// Hiding a banned ad from the board is pointless if its host can still push it to arbitrary
		// players by name. Keyed on the party's host rather than the sender, so a banned host
		// cannot route around it by asking a party-mate to send the invites for them. The ack
		// claims delivery, because an invite that visibly fails is a ban notification.
		if (bans.isHidden(party)) {
			log.info("WS invite suppressed (host banned): party={} target={}", id, target);
			send(sub, Outbound.inviteAck(version.get(), in.target(), true));
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

	/**
	 * Records a player's report of an advertisement and, subject to rate limiting, forwards it to
	 * Discord for review.
	 *
	 * <p>Deliberately silent in both directions. The client gets no acknowledgement of any kind:
	 * an ack that distinguished "recorded" from "rate-limited" would tell an abuser exactly which
	 * of their reports landed, and telling a reporter their report was throttled invites them to
	 * retry. The plugin shows success unconditionally and treats this as fire-and-forget.
	 */
	private void handleReport(Subscriber sub, Inbound in) {
		if (!reportsEnabled) {
			return;
		}
		String id = in.id();
		if (id == null) {
			return;
		}
		// One report per advertisement per session. Trivially bypassed by reconnecting, which is
		// exactly why it is the weakest of the layers and not the one being relied on.
		//
		// Note the ordering: the id is only remembered once it has been checked against a real
		// party, and the cap is enforced before that. Recording ids up front would let a client
		// grow this set without bound by reporting made-up ids, turning a rate limit into a memory
		// leak.
		if (sub.reportedPartyIds.contains(id)) {
			reportSuppressed("duplicate-session");
			return;
		}
		if (sub.reportedPartyIds.size() >= reportsPerSession) {
			reportSuppressed("session-cap");
			return;
		}
		Party party = store.findById(id).orElse(null);
		if (party == null) {
			reportSuppressed("unknown-party");
			return;
		}
		if (isOwnParty(sub, party)) {
			reportSuppressed("self-report");
			return;
		}
		if (bans.isHidden(party)) {
			// Already handled; re-reporting a banned host would just re-notify moderators.
			reportSuppressed("already-banned");
			return;
		}
		String clientIp = (String) sub.session.getAttributes()
			.get(ClientAddressHandshakeInterceptor.CLIENT_IP_ATTRIBUTE);
		if (!rateLimiter.withinIpBudget(clientIp)) {
			reportSuppressed("ip-cap");
			return;
		}
		sub.reportedPartyIds.add(id);
		reportsReceived.increment();

		String normalizedHost = normalizeName(party.getHost());
		String fingerprint = sub.accountHash != null ? "a:" + sub.accountHash
			: (sub.name != null ? "n:" + sub.name : "s:" + sub.session.getId());
		ReportRateLimiter.Decision decision =
			rateLimiter.evaluate(id, normalizedHost == null ? "" : normalizedHost, fingerprint);

		long reportId;
		try {
			reportId = reports.insert(buildReport(sub, party, normalizedHost, rateLimiter.hash(clientIp)));
		}
		catch (Exception e) {
			log.warn("Failed to record report for party {}: {}", id, e.toString());
			return;
		}
		log.info("WS report: session={} party={} host={} distinctReporters={} notify={} reason={}",
			sub.session.getId(), id, party.getHost(), decision.distinctReporters(),
			decision.shouldNotify(), decision.reason());

		if (!decision.shouldNotify()) {
			reportSuppressed(decision.reason());
			return;
		}
		adReports.publish(new net.osparty.api.service.AdReportService.ReviewRequest(
				reportId, party.getHost(), party.getHostAccountHash(), party.getActivity(),
				party.getDescription(), party.getWorld(), party.getCapacity(), party.getSize(),
				party.getInviteCode(), sub.name, decision.distinctReporters()))
			.ifPresent(posted -> {
				reports.markNotified(reportId, posted.channelId(), posted.messageId());
				reportsNotified.increment();
			});
	}

	private net.osparty.api.model.AdReport buildReport(Subscriber sub, Party party,
		String normalizedHost, String ipHash) {
		net.osparty.api.model.AdReport report = new net.osparty.api.model.AdReport();
		report.setPartyId(party.getId());
		report.setHostName(normalizedHost == null ? "" : normalizedHost);
		report.setHostNameRaw(party.getHost());
		report.setHostAccountHash(party.getHostAccountHash() == 0 ? null : party.getHostAccountHash());
		report.setActivity(party.getActivity());
		report.setDescription(party.getDescription());
		report.setWorld(party.getWorld());
		report.setCapacity(party.getCapacity());
		report.setPartySize(party.getSize());
		report.setInviteCode(party.getInviteCode());
		report.setAdSnapshot(snapshotJson(party));
		report.setReporterName(sub.name);
		report.setReporterAccountHash(sub.accountHash);
		report.setReporterSessionId(sub.session.getId());
		report.setReporterIpHash(ipHash);
		return report;
	}

	/** The advertisement verbatim: the only surviving evidence once the 90s ad TTL elapses. */
	private String snapshotJson(Party party) {
		try {
			return mapper.writeValueAsString(party);
		}
		catch (Exception e) {
			log.warn("Failed to serialise ad snapshot for party {}: {}", party.getId(), e.toString());
			return "{}";
		}
	}

	private void reportSuppressed(String reason) {
		io.micrometer.core.instrument.Counter.builder("osparty.reports.suppressed")
			.description("Advertisement reports recorded or dropped without notifying Discord")
			.tag("reason", reason == null ? "unknown" : reason)
			.register(meterRegistry)
			.increment();
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

	/**
	 * Normalise an OSRS name for identity matching, returning null rather than an empty string when
	 * there is no usable name. Delegates to {@link PartyFactory#normalizeHost} so that socket
	 * identity, the Redis {@code partyhost:} index and the ban tables all key on one identity space
	 * -- two subtly different normalisers here would mean a ban that matches the board but not a
	 * lookup, or vice versa.
	 */
	private static String normalizeName(String name) {
		if (name == null) {
			return null;
		}
		String normalized = PartyFactory.normalizeHost(name);
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

	/**
	 * Publish the board the reconciler just built, so subscribers that connect before the next tick are
	 * served from it rather than each re-reading and re-serialising the whole thing.
	 *
	 * <p>Also makes the snapshot and the delta stream consistent by construction: both now describe the same
	 * instant, where before a joiner could be sent a board newer than the one the next batch was diffed
	 * against.
	 *
	 * @param visible what everyone may see.
	 * @param hidden shadowbanned ads — almost always empty, and only their own hosts are shown them.
	 */
	void publishBoard(List<Party> visible, List<Party> hidden,
		Map<String, PartyReconciler.Tombstone> tombstones, long prunedThroughSeq) {
		board = new Board(version.get(), List.copyOf(visible), List.copyOf(hidden),
			tombstones, prunedThroughSeq);
	}

	/**
	 * Sends the current board. Shadowbanned ads are stripped, except the subscriber's own: a host
	 * who cannot see their own advertisement in their own Search tab would notice immediately, and
	 * the ban would stop being silent.
	 *
	 * <p>Served from the shared board wherever possible. Building it per subscriber meant a Redis read of
	 * every ad, a badge enrichment pass and a fresh serialisation of ~200 KB on <em>every connect</em> —
	 * which made reconnects the most expensive thing this service does, exactly when it is least able to
	 * afford them. The frame for a given activity is built once per reconcile and handed to everyone.
	 *
	 * <p>The shared board is as of the last reconcile, so it can be up to one tick behind: an ad created
	 * seconds ago may be missing from it. For a stranger that is invisible — the next batch delivers it as a
	 * {@code created}, which is the same latency the delta stream has anyway. For a <em>host</em> it is not:
	 * seeing their own advertisement on the board is how they confirm they are advertised at all, and it is
	 * their own ad that is most likely to be newer than the last reconcile. So hosts get a board of their
	 * own, as do the hosts of hidden ads, and everyone else — the large majority — is served from the cache.
	 *
	 * <p>The per-subscriber path therefore remains for three cases: before the first reconcile has run, for
	 * anyone hosting on this session, and for a host whose own ad is shadowbanned.
	 */
	private void sendSnapshot(Subscriber sub, Long since) {
		Board current = board;
		// A client that still holds a board only needs what it missed. That is what makes a reconnect
		// cheap — and a rolling deploy, which today re-sends the entire board to every client at once.
		if (since != null && current != null && !hostedBy.containsKey(sub.session.getId())
			&& !current.showsOwnHidden(sub, this)) {
			Frame resume = current.resume(sub.activity, since, this);
			if (resume != null) {
				sendRaw(sub, resume);
				return;
			}
		}
		if (current != null && !hostedBy.containsKey(sub.session.getId())
			&& !current.showsOwnHidden(sub, this)) {
			Frame frame = current.frame(sub.activity, this);
			if (frame != null) {
				sendRaw(sub, frame);
				return;
			}
		}
		List<Party> all = badges.enrichParties(store.list(sub.activity));
		List<Party> list = new java.util.ArrayList<>(all.size());
		for (Party party : all) {
			if (!bans.isHidden(party) || isOwnParty(sub, party)) {
				list.add(party);
			}
		}
		log.debug("WS snapshot -> {} ({} of {} parties)", sub.session.getId(), list.size(), all.size());
		send(sub, Outbound.snapshot(version.get(), list, headSeq(list)));
	}

	/** The highest revision among a list of ads — what a client resumes from after a full board. */
	private static Long headSeq(List<Party> parties) {
		long head = 0;
		for (Party party : parties) {
			head = Math.max(head, party.getSeq());
		}
		return head > 0 ? head : null;
	}

	/**
	 * One reconcile's board, plus the frames built from it.
	 *
	 * <p>Frames are memoised per activity rather than built up front: subscribers overwhelmingly watch
	 * everything, so building one per known activity would serialise a dozen boards nobody asked for.
	 */
	private static final class Board {
		private final long version;
		private final List<Party> visible;
		private final List<Party> hidden;
		private final Map<String, PartyReconciler.Tombstone> tombstones;
		/** Removals older than this are forgotten, so a client asking from before it needs the whole board. */
		private final long prunedThroughSeq;
		/** activity ("" for the all-activities firehose) -> the frame to send. */
		private final Map<String, Frame> frames = new ConcurrentHashMap<>();

		Board(long version, List<Party> visible, List<Party> hidden,
			Map<String, PartyReconciler.Tombstone> tombstones, long prunedThroughSeq) {
			this.version = version;
			this.visible = visible;
			this.hidden = hidden;
			this.tombstones = tombstones;
			this.prunedThroughSeq = prunedThroughSeq;
		}

		/** The highest revision anything on this board carries — what a client should resume from next. */
		long headSeq() {
			long head = prunedThroughSeq;
			for (Party party : visible) {
				head = Math.max(head, party.getSeq());
			}
			for (PartyReconciler.Tombstone stone : tombstones.values()) {
				head = Math.max(head, stone.seq());
			}
			return head;
		}

		/**
		 * What changed since {@code since}, for a client that still holds the rest, or null if it has been
		 * away too long to be caught up and needs the board itself.
		 */
		Frame resume(String activity, long since, PartyBroadcaster owner) {
			if (since <= 0 || since <= prunedThroughSeq) {
				return null;
			}
			List<Party> created = new java.util.ArrayList<>();
			for (Party party : visible) {
				if (party.getSeq() > since && matches(activity, party.getActivity())) {
					created.add(party);
				}
			}
			List<String> removed = new java.util.ArrayList<>();
			for (Map.Entry<String, PartyReconciler.Tombstone> entry : tombstones.entrySet()) {
				if (entry.getValue().seq() > since && matches(activity, entry.getValue().activity())) {
					removed.add(entry.getKey());
				}
			}
			// An empty resume is still an answer: it tells the client its board is current.
			Batch batch = new Batch("batch", version, created.isEmpty() ? null : created, null,
				removed.isEmpty() ? null : removed, headSeq());
			try {
				return new Frame(new TextMessage(owner.mapper.writeValueAsString(batch)));
			}
			catch (Exception e) {
				log.warn("Failed to serialise a resume frame", e);
				return null;
			}
		}

		private static boolean matches(String activity, String adActivity) {
			return activity == null || activity.isEmpty() || activity.equals(adActivity);
		}

		/** Whether this subscriber must be served a board of its own because one of its ads is hidden. */
		boolean showsOwnHidden(Subscriber sub, PartyBroadcaster owner) {
			if (hidden.isEmpty()) {
				return false;
			}
			for (Party party : hidden) {
				if (owner.isOwnParty(sub, party)) {
					return true;
				}
			}
			return false;
		}

		Frame frame(String activity, PartyBroadcaster owner) {
			return frames.computeIfAbsent(activity == null ? "" : activity, key -> {
				List<Party> list = visible;
				if (!key.isEmpty()) {
					list = new java.util.ArrayList<>();
					for (Party party : visible) {
						if (key.equals(party.getActivity())) {
							list.add(party);
						}
					}
				}
				try {
					return new Frame(new TextMessage(owner.mapper.writeValueAsString(
						Outbound.snapshot(version, list, headSeq()))));
				}
				catch (Exception e) {
					log.warn("Failed to serialise the shared snapshot frame", e);
					return null;
				}
			});
		}
	}

	/**
	 * Whether this advertisement belongs to this subscriber.
	 *
	 * <p>Checked three ways because no single one is reliable at every moment of a session:
	 * {@code hostedBy} is only bound once the client has sent {@code host} or {@code resume}, and
	 * {@code identify} is optional and may arrive after {@code subscribe}. The plugin looks its own
	 * ad up before either is guaranteed -- on the rejoin-after-restart path, and on every heartbeat
	 * from the Party tab, where a null answer makes it disband the party and tell the user so. A
	 * missed self-check there would turn a silent ban into a very loud one.
	 */
	private boolean isOwnParty(Subscriber sub, Party party) {
		if (party == null) {
			return false;
		}
		if (party.getId().equals(hostedBy.get(sub.session.getId()))) {
			return true;
		}
		if (sub.accountHash != null && party.getHostAccountHash() != 0
			&& sub.accountHash == party.getHostAccountHash()) {
			return true;
		}
		return sub.name != null && sub.name.equals(normalizeName(party.getHost()));
	}

	private void sendError(Subscriber sub, String id, String detail) {
		send(sub, Outbound.error(version.get(), id, detail));
	}

	/**
	 * Fans a reconciler delta out to every subscriber, serialising each distinct view once.
	 *
	 * <p>The memoisation key carries a second component beyond the activity filter: the id of a
	 * party whose removal must be withheld from this particular subscriber. That happens only when
	 * a party has just been hidden by a ban and the subscriber is the host who owns it -- the whole
	 * point of a shadowban is that its subject sees nothing change. On every other tick the
	 * component is empty for everybody, so the cache collapses back to one entry per activity and
	 * costs nothing.
	 */
	void broadcastBatch(List<Party> created, List<PartyDelta> updated, List<RemovedRef> removed) {
		if (created.isEmpty() && updated.isEmpty() && removed.isEmpty()) {
			return;
		}
		long v = version.incrementAndGet();
		// Computed once per tick, and empty on virtually every tick.
		java.util.Set<String> hiddenIds = null;
		for (RemovedRef ref : removed) {
			if (ref.hidden()) {
				if (hiddenIds == null) {
					hiddenIds = new java.util.HashSet<>(2);
				}
				hiddenIds.add(ref.id());
			}
		}
		Map<BatchKey, Frame> perKey = new java.util.HashMap<>();
		for (Subscriber sub : subscribers.values()) {
			if (!sub.subscribed || !sub.session.isOpen()) {
				continue;
			}
			String suppress = null;
			if (hiddenIds != null) {
				String own = hostedBy.get(sub.session.getId());
				if (own != null && hiddenIds.contains(own)) {
					suppress = own;
				}
			}
			BatchKey key = new BatchKey(sub.activity, suppress);
			Frame frame = perKey.computeIfAbsent(key,
				k -> buildBatch(v, k.activity(), created, updated, removed, k.suppress()));
			if (frame != null) {
				sendRaw(sub, frame);
			}
		}
	}

	private Frame buildBatch(long v, String activity, List<Party> created, List<PartyDelta> updated,
		List<RemovedRef> removed, String suppress) {
		List<Party> c = filterCreated(created, activity);
		List<PartyDelta> u = filterUpdated(updated, activity);
		List<String> r = new java.util.ArrayList<>();
		for (RemovedRef ref : removed) {
			if (ref.id().equals(suppress)) {
				continue;
			}
			if (activity == null || activity.equals(ref.activity())) {
				r.add(ref.id());
			}
		}
		if (c.isEmpty() && u.isEmpty() && r.isEmpty()) {
			return null;
		}
		long head = 0;
		for (Party party : c) {
			head = Math.max(head, party.getSeq());
		}
		Board current = board;
		if (current != null) {
			head = Math.max(head, current.headSeq());
		}
		Batch batch = new Batch("batch", v, c.isEmpty() ? null : c, u.isEmpty() ? null : u,
			r.isEmpty() ? null : r, head > 0 ? head : null);
		try {
			return new Frame(new TextMessage(mapper.writeValueAsString(batch)));
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

	/** Send a shared frame in whichever form this subscriber asked for. */
	private void sendRaw(Subscriber sub, Frame frame) {
		org.springframework.web.socket.WebSocketMessage<?> message = sub.compressed ? frame.compressed() : null;
		sendRaw(sub, message != null ? message : frame.text());
	}

	private void sendRaw(Subscriber sub, org.springframework.web.socket.WebSocketMessage<?> frame) {
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

	/**
	 * A party leaving the public board.
	 *
	 * @param hidden true when the record still exists and was merely shadowbanned out of view,
	 *     false when it genuinely went away. Only the latter may tear down a voice channel, and
	 *     only the former is withheld from the host it concerns.
	 */
	record RemovedRef(String id, String activity, boolean hidden) {
	}

	/**
	 * A shared frame, in both the forms a client may want it: the JSON text every version understands, and
	 * the gzipped bytes newer ones ask for.
	 *
	 * <p>Compression pays here in a way it did not for the live party. Ad-board frames are few and enormous
	 * — a snapshot is the whole board, a batch carries hundreds of deltas — and they are repetitive JSON,
	 * which deflates several-fold. They are also <em>already shared</em>: one frame serves every subscriber
	 * with the same filter, so the compression is done once and sent many times, rather than once per
	 * connection as a negotiated WebSocket extension would do it. That is the difference between paying for
	 * it once a tick and paying for it once a tick per subscriber.
	 *
	 * <p>Built lazily: a board with no compression-capable subscribers never pays for it.
	 */
	private static final class Frame {
		private final TextMessage text;
		private volatile BinaryMessage compressed;

		Frame(TextMessage text) {
			this.text = text;
		}

		TextMessage text() {
			return text;
		}

		BinaryMessage compressed() {
			BinaryMessage local = compressed;
			if (local != null) {
				return local;
			}
			// Racing threads may both compress; both results are equivalent and the loser is garbage.
			try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
				try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
					gzip.write(text.asBytes());
				}
				local = new BinaryMessage(out.toByteArray());
			}
			catch (Exception e) {
				log.warn("Failed to compress a shared frame; falling back to text", e);
				return null;
			}
			compressed = local;
			return local;
		}
	}

	/** Identifies one distinct serialisation of a batch frame. See {@link #broadcastBatch}. */
	private record BatchKey(String activity, String suppress) {
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
		/** Whether this client asked for gzipped binary frames rather than JSON text. */
		volatile boolean compressed;
		volatile String activity;
		// Self-reported identity, mirrored into sessionByAccount/sessionByName for invite routing.
		volatile Long accountHash;
		volatile String name;
		/** Advertisements reported on this connection, so each is only reported once per session. */
		final java.util.Set<String> reportedPartyIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

		Subscriber(WebSocketSession session) {
			this.session = session;
		}
	}

	record Inbound(String type, String activity, PartyRequest request, PartyUpdate patch, String id, String key,
		String code, String host, Long accountHash, Boolean visible, String newKey, String name, String target,
		/** Sent with {@code subscribe}: this client can read gzipped binary frames. Absent means it cannot. */
		Boolean compress,
		/**
		 * Sent with {@code subscribe} by a client that still holds a board: the revision it last applied.
		 * Answered with the changes since, or with a whole board when it has been away too long.
		 */
		Long since) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Outbound(String type, long version, List<Party> parties, Party party, String id, String detail,
		Integer online, String url, String username, Long accountHash, Boolean badgesVisible, String from,
		Boolean delivered,
		/** Snapshot only: the revision this board is current to, which the client offers back on resume. */
		Long seq) {
		static Outbound snapshot(long version, List<Party> parties, Long seq) {
			return new Outbound("snapshot", version, parties, null, null, null, null, null, null, null, null, null, null, seq);
		}

		static Outbound hosted(long version, Party party) {
			return new Outbound("hosted", version, null, party, null, null, null, null, null, null, null, null, null, null);
		}

		static Outbound gone(long version, String id) {
			return new Outbound("gone", version, null, null, id, null, null, null, null, null, null, null, null, null);
		}

		static Outbound error(long version, String id, String detail) {
			return new Outbound("error", version, null, null, id, detail, null, null, null, null, null, null, null, null);
		}

		static Outbound byCode(long version, String code, Party party) {
			return new Outbound("byCode", version, null, party, code, null, null, null, null, null, null, null, null, null);
		}

		static Outbound byHost(long version, String host, Party party) {
			return new Outbound("byHost", version, null, party, host, null, null, null, null, null, null, null, null, null);
		}

		static Outbound presence(long version, int online) {
			return new Outbound("presence", version, null, null, null, null, online, null, null, null, null, null, null, null);
		}

		static Outbound voiceChannel(long version, String id, String url) {
			return new Outbound("voiceChannel", version, null, null, id, null, null, url, null, null, null, null, null, null);
		}

		static Outbound discordLinkUrl(long version, String url) {
			return new Outbound("discordLinkUrl", version, null, null, null, null, null, url, null, null, null, null, null, null);
		}

		static Outbound discordLink(long version, long accountHash, String discordId, String username,
			Boolean badgesVisible) {
			return new Outbound("discordLink", version, null, null, discordId, null, null, null, username,
				accountHash, badgesVisible, null, null, null);
		}

		static Outbound voiceAccess(long version, String id) {
			return new Outbound("voiceAccess", version, null, null, id, null, null, null, null, null, null, null, null, null);
		}

		static Outbound transferred(long version, String id) {
			return new Outbound("transferred", version, null, null, id, null, null, null, null, null, null, null, null, null);
		}

		static Outbound invited(long version, Party party, String from) {
			return new Outbound("invited", version, null, party, null, null, null, null, null, null, null, from, null, null);
		}

		static Outbound inviteAck(long version, String target, boolean delivered) {
			return new Outbound("inviteAck", version, null, null, target, null, null, null, null, null, null, null,
				delivered, null);
		}
	}

	/**
	 * A tick's worth of board changes. {@code seq} is the highest revision it carries: a client stores it
	 * and offers it back on its next {@code subscribe}, which is what lets a reconnect cost a handful of
	 * deltas instead of the whole board.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Batch(String type, long version, List<Party> created, List<PartyDelta> updated,
		List<String> removed, Long seq) {
	}
}

package net.osparty.api.web.ws;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.osparty.api.repository.AdvertisementRepository;
import net.osparty.api.repository.AdvertisementRepository.Authorization;
import net.osparty.api.model.Advertisement;
import net.osparty.api.model.AdvertisementDelta;
import net.osparty.api.model.AdvertisementRequest;
import net.osparty.api.model.AdvertisementUpdate;
import net.osparty.api.service.DiscordLinkService;
import net.osparty.api.service.AdvertisementFactory;
import net.osparty.api.service.ReportRateLimiter;
import net.osparty.api.service.VoiceChannelService;
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

@Component
@ConditionalOnProperty(name = "app.ws.enabled", havingValue = "true", matchIfMissing = true)
public class BoardBroadcaster implements net.osparty.api.party.HostedAds {
	private static final Logger log = LoggerFactory.getLogger(BoardBroadcaster.class);

	private static final AdvertisementUpdate TTL_TOUCH = new AdvertisementUpdate();

	/**
	 * Recovery codes a single connection may try.
	 *
	 * <p>Not what makes guessing hopeless -- eighty bits does that. This is here so a client stuck in a loop,
	 * or someone feeding a list through, is stopped rather than served, and it is per connection because
	 * that is the only scope available before a caller has proved who it is.
	 */
	private static final int MAX_RECOVERY_ATTEMPTS_PER_SESSION = 10;

	private final AdvertisementRepository store;
	private final ObjectMapper mapper;
	private final net.osparty.api.service.VoiceChannelService voice;
	private final net.osparty.api.service.DiscordLinkService discordLinks;
	private final net.osparty.api.service.DiscordBadgeService badges;
	private final PresenceRegistry presence;
	private final InviteBus inviteBus;
	private final net.osparty.api.service.BanService bans;
	/** Announces an ad's change to every node, so nobody has to re-scan the board to find it. */
	private final BoardChangeBus changes;
	/**
	 * Seats we have promised, so the live-party side can auto-admit on our record instead of on the joiner's
	 * own say-so. Written here because this is the only place that knows both the advertisement and the room
	 * key the party side will ask about.
	 */
	private final net.osparty.api.party.PartyAdmissionService admissions;
	/** Mints and resolves the per-install credential that settles who a connection is. */
	private final net.osparty.api.service.AccountAuthService auth;
	/**
	 * The way back onto an account when no other machine can vouch for it. Null where the board is built
	 * without Discord at all, which the recovery frames check for rather than assume.
	 */
	private final net.osparty.api.service.DiscordRecoveryService discordRecovery;
	/** Reaches an account's other machines wherever they are; without it coupling only worked per replica. */
	private final CouplingBus couplings;
	/** Turns an account hash into the public id other players see, so the hash itself never has to travel. */
	private final net.osparty.api.service.PlayerIdService playerIds;
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
	private final Map<String, String> hostedAdBySession = new ConcurrentHashMap<>();
	private final Map<String, String> ownerSession = new ConcurrentHashMap<>();
	// Self-asserted identity indexes so an invite can be routed to a specific online client.
	private final Map<Long, String> sessionByAccount = new ConcurrentHashMap<>();
	private final Map<String, String> sessionByName = new ConcurrentHashMap<>();
	private final AtomicLong version = new AtomicLong();
	private volatile int lastPresence = -1;
	/** The board as of the last reconcile, shared by every subscriber that joins before the next one. */
	private volatile Board board;

	public BoardBroadcaster(AdvertisementRepository store, ObjectMapper mapper,
		net.osparty.api.service.VoiceChannelService voice,
		net.osparty.api.service.DiscordLinkService discordLinks,
		net.osparty.api.service.DiscordBadgeService badges,
		PresenceRegistry presence,
		InviteBus inviteBus,
		CouplingBus couplingBus,
		net.osparty.api.service.BanService bans,
		BoardChangeBus changes,
		net.osparty.api.party.PartyAdmissionService admissions,
		net.osparty.api.service.AccountAuthService auth,
		net.osparty.api.service.DiscordRecoveryService discordRecovery,
		net.osparty.api.service.PlayerIdService playerIds,
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
		this.couplings = couplingBus;
		this.bans = bans;
		this.changes = changes;
		this.admissions = admissions;
		this.auth = auth;
		this.discordRecovery = discordRecovery;
		this.playerIds = playerIds;
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
		// Same arrangement for coupling: the bus asks every node, and this is how this one answers.
		couplings.setLocalHandlers(this::hasLocalDeviceOnline, this::sendCouplingCodeLocally);
		Gauge.builder("parties.active", store, AdvertisementRepository::advertisementCount)
				.description("Current number of active parties")
				.register(meterRegistry);
	}

	public int activeConnections() {
		return subscribers.size();
	}

	/**
	 * A client arrived. The board knows nothing about what carries its bytes — {@code clientIp} is handed in
	 * because the transport is the only thing that can see the peer address, and the report rate limiter
	 * needs it.
	 */
	public void onOpen(net.osparty.api.transport.SocketSession session, String clientIp) {
		onOpen(session, clientIp, null, null);
	}

	/**
	 * @param authenticated the account this connection proved during the handshake, or null if it presented
	 *     no credential. When set, {@code identify} can no longer move this session onto another account and
	 *     the Discord frames act on this one whatever they name.
	 */
	public void onOpen(net.osparty.api.transport.SocketSession session, String clientIp, Long authenticated) {
		onOpen(session, clientIp, authenticated, null);
	}

	/**
	 * @param deviceLabel a client-reported name for this device (e.g. its hostname), carried only as far as
	 *     an enrolment that happens on this connection -- see {@link AccountAuthService#enrol}.
	 */
	public void onOpen(net.osparty.api.transport.SocketSession session, String clientIp, Long authenticated,
		String deviceLabel) {
		Subscriber sub = new Subscriber(session, clientIp, deviceLabel);
		if (authenticated != null) {
			sub.authenticated = true;
			sub.accountHash = authenticated;
			sessionByAccount.put(authenticated, session.id());
		}
		subscribers.put(session.id(), sub);
		log.info("WS connected: session={} (subscribers={})",
			session.id(), subscribers.size());
		send(sub, Outbound.presence(version.get(), lastPresence >= 0 ? lastPresence : subscribers.size()));
	}

	/** One inbound frame, addressed by session id so the transport never hands over its own object. */
	public void onMessage(String sessionId, String payload) {
		Subscriber sub = subscribers.get(sessionId);
		if (sub == null) {
			return;
		}
		Inbound in;
		try {
			in = mapper.readValue(payload, Inbound.class);
		}
		catch (Exception e) {
			// A frame this build cannot read. Between plugin versions that is what a protocol difference
			// looks like, and dropping it in silence is why it presents as a server that stopped answering.
			if (sub.firstTime("<unparseable>")) {
				log.warn("Board frame unparseable: session={} error={} frame={}",
					sub.session.id(), e.toString(), preview(payload));
			}
			return;
		}
		if (in.type() == null) {
			if (sub.firstTime("<untyped>")) {
				log.warn("Board frame carries no type: session={} frame={}",
					sub.session.id(), preview(payload));
			}
			return;
		}
		log.debug("WS <- {} {}", sub.session.id(), payload);
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
			case "requestCouplingCode":
				handleRequestCouplingCode(sub, in);
				break;
			case "couplingConfirm":
				handleCouplingConfirm(sub, in);
				break;
			case "retryAuth":
				handleRetryAuth(sub, in);
				break;
			case "recoveryConfirm":
				handleRecoveryConfirm(sub, in);
				break;
			case "issueRecoveryCodes":
				handleIssueRecoveryCodes(sub);
				break;
			case "recoveryStatus":
				handleRecoveryStatus(sub);
				break;
			case "startDiscordRecovery":
				handleStartDiscordRecovery(sub, in);
				break;
			case "discordRecoveryPoll":
				handleDiscordRecoveryPoll(sub, in);
				break;
			case "listDevices":
				handleListDevices(sub);
				break;
			case "revokeDevice":
				handleRevokeDevice(sub, in);
				break;
			case "renameDevice":
				handleRenameDevice(sub, in);
				break;
			case "invite":
				handleInvite(sub, in);
				break;
			case "report":
				handleReport(sub, in);
				break;
			default:
				if (sub.firstTime(in.type())) {
					log.warn("Board frame of unknown type: session={} type={}", sub.session.id(), in.type());
				}
				break;
		}
	}

	/** The first of a frame, for a log line about one that could not be handled. */
	private static String preview(String payload) {
		if (payload == null) {
			return "<none>";
		}
		return payload.length() <= 300 ? payload
			: payload.substring(0, 300) + "…(" + payload.length() + " chars)";
	}

	private void handleSubscribe(Subscriber sub, Inbound in) {
		sub.activity = (in.activity() == null || in.activity().isBlank()) ? null : in.activity();
		// Opt-in, and read before the snapshot goes out so the first frame is already compressed. A client
		// that says nothing keeps getting text, which is what every version before this one expects.
		sub.compressed = Boolean.TRUE.equals(in.compress());
		sub.subscribed = true;
		log.info("WS subscribe: session={} activity={}", sub.session.id(),
			sub.activity == null ? "<all>" : sub.activity);
		sendSnapshot(sub, in.since());
	}

	private void handleUnsubscribe(Subscriber sub) {
		sub.subscribed = false;
	}

	private void handleGetByCode(Subscriber sub, Inbound in) {
		String code = in.code();
		Advertisement ad = code == null ? null : store.findByInviteCode(code).orElse(null);
		boolean hidden = filterByCode && hiddenFromViewer(sub, ad);
		if (hidden) {
			ad = null;
		}
		// A miss and a suppression are the same empty answer to the client, and the code it typed is the
		// only thing that says which. Logged at info because it is a deliberate act by a user who is now
		// looking at a party that will not open, once per act.
		log.info("WS getByCode: session={} code={} result={}", sub.session.id(), code,
			ad != null ? "ad=" + ad.getId() : hidden ? "hidden" : "no such code");
		send(sub, Outbound.byCode(version.get(), code, enriched(ad)));
	}

	private void handleGetByHost(Subscriber sub, Inbound in) {
		String host = in.host();
		Advertisement ad = host == null ? null : store.findByHost(host).orElse(null);
		boolean hidden = filterByHost && hiddenFromViewer(sub, ad);
		if (hidden) {
			ad = null;
		}
		log.info("WS getByHost: session={} host={} result={}", sub.session.id(), host,
			ad != null ? "ad=" + ad.getId() : hidden ? "hidden" : "not hosting");
		send(sub, Outbound.byHost(version.get(), host, enriched(ad)));
	}

	/** Shadowbanned, and not this viewer's own advertisement. See {@link #isOwnAdvertisement}. */
	private boolean hiddenFromViewer(Subscriber sub, Advertisement ad) {
		return ad != null && bans.isHidden(ad) && !isOwnAdvertisement(sub, ad);
	}

	private void handleHost(Subscriber sub, Inbound in) {
		if (in.request() == null) {
			sendError(sub, null, "missing request");
			return;
		}
		Advertisement ad = store.create(in.request(), in.key());
		bind(sub.session.id(), ad.getId());
		log.info("WS host: session={} party={} host={}", sub.session.id(), ad.getId(), ad.getHost());
		send(sub, Outbound.hosted(version.get(), enriched(ad)));
		// After the host's own ack, always: the announcement puts the ad on everyone's board, and a host
		// should learn its advertisement exists before the rest of the world does.
		changes.publish(ad.getId(), ad.getSeq());
	}

	private Advertisement enriched(Advertisement ad) {
		return ad == null ? null : playerIds.enrichAds(badges.enrichAds(List.of(ad))).get(0);
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
		Optional<Advertisement> updated = store.update(id, in.patch() == null ? TTL_TOUCH : in.patch());
		if (updated.isEmpty()) {
			sendError(sub, id, "gone");
			unbind(sub.session.id());
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
		Optional<Advertisement> ad = store.update(id, TTL_TOUCH);
		if (ad.isEmpty()) {
			send(sub, Outbound.gone(version.get(), id));
			return;
		}
		bind(sub.session.id(), id);
		log.info("WS resume: session={} party={}", sub.session.id(), id);
		send(sub, Outbound.hosted(version.get(), enriched(ad.get())));
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
		log.info("WS unhost: session={} party={}", sub.session.id(), id);
		removeHostedAd(id, sub.session.id());
	}

	/**
	 * Take {@code id} off the board: delete it, free any voice channel it held, unbind the session that
	 * hosted it and announce the removal.
	 */
	private void removeHostedAd(String id, String sessionId) {
		Advertisement deleted = store.delete(id).orElse(null);
		if (deleted != null && deleted.getDiscordChannelId() != null) {
			voice.delete(deleted.getDiscordChannelId());
		}
		unbind(sessionId);
		// A removal has no advertisement left to stamp, so it takes the next revision from the same
		// sequence — which is what lets every node order it against everything else.
		changes.publish(id, store.nextRevision());
	}

	/**
	 * The party module swept this session's room out from under it, so its advertisement goes too.
	 *
	 * <p>Told rather than deduced: nothing the board can see distinguishes a host whose room is gone from
	 * one sitting in a quiet party, and {@link #touchOwnedAds} would go on renewing the ad either way.
	 * The {@code gone} frame is the same one a purged host receives, so a client that is merely idle folds
	 * its hosting state on a path it already implements.
	 */
	@Override
	public void dropHostedBy(String sessionId) {
		String id = hostedAdBySession.get(sessionId);
		if (id == null) {
			// Already unbound: the session closed cleanly and took its binding with it, leaving the ad to
			// expire on its own TTL as it always has.
			return;
		}
		log.info("Party sweep: dropping ad {} — session {} no longer hosts a room", id, sessionId);
		removeHostedAd(id, sessionId);
		Subscriber sub = subscribers.get(sessionId);
		if (sub != null) {
			send(sub, Outbound.gone(version.get(), id));
		}
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
		Optional<Advertisement> ad = store.transferHost(id, in.host(), in.hostAccountType(), in.newKey());
		if (ad.isEmpty()) {
			sendError(sub, id, "gone");
			unbind(sub.session.id());
			return;
		}
		unbind(sub.session.id());
		// The Discord channel name embeds the host, so rename it to match the new host (best-effort).
		if (ad.get().getDiscordChannelId() != null) {
			voice.rename(ad.get().getDiscordChannelId(), ad.get());
		}
		log.info("WS transferHost: session={} party={} newHost={}", sub.session.id(), id, in.host());
		send(sub, Outbound.transferred(version.get(), id));
		changes.publish(id, ad.get().getSeq());
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
		Advertisement ad = store.findById(id).orElse(null);
		if (ad == null) {
			sendError(sub, id, "gone");
			unbind(sub.session.id());
			return;
		}
		if (ad.getDiscordInviteUrl() != null) {
			send(sub, Outbound.voiceChannel(version.get(), id, ad.getDiscordInviteUrl()));
			return;
		}
		Optional<VoiceChannelService.VoiceChannelInfo> channel =
			voice.createForParty(ad, linkedDiscordIds(ad));
		if (channel.isEmpty()) {
			sendError(sub, id, "voice unavailable");
			return;
		}
		store.attachVoiceChannel(id, channel.get().channelId(), channel.get().inviteUrl());
		log.info("WS voice channel: session={} party={} channel={}", sub.session.id(), id,
			channel.get().channelId());
		send(sub, Outbound.voiceChannel(version.get(), id, channel.get().inviteUrl()));
		changes.publish(id, store.findById(id).map(Advertisement::getSeq).orElse(0L));
	}

	/**
	 * Whether this connection may act on {@code in}'s account for the Discord/badge frames, all of which
	 * name the account they operate on and used to act on whichever one they were given -- so one client
	 * could read another player's Discord id, unlink them, or flip their badge.
	 *
	 * <p>This cannot be fixed properly here. The check that belongs in this spot is "is this connection that
	 * account", and nothing on the socket can answer it until the connection is authenticated -- which the
	 * clients already in the wild cannot do. So the rule is the strongest one that no existing client trips:
	 * a connection that has said who it is has to keep saying the same thing, and one that never identified
	 * is left alone. That closes acting on someone else's account from a session identified as your own, and
	 * puts the rest in the log where it can be seen.
	 */
	private boolean actingOnOwnAccount(Subscriber sub, Inbound in, String frame) {
		if (sub.accountHash == null || sub.accountHash.equals(in.accountHash())) {
			return true;
		}
		log.warn("Refused {}: session={} identified as {} but acted on {}",
			frame, sub.session.id(), sub.accountHash, in.accountHash());
		return false;
	}

	/**
	 * The stronger check, for the two frames that decide what a Discord link means: this connection must
	 * <em>be</em> the account, not merely have named it.
	 *
	 * <p>{@link #actingOnOwnAccount} is a speed bump -- a session that never identified passes it. That was
	 * tolerable while a link only lit up a badge. It is not tolerable now that a link is accepted as proof of
	 * ownership during recovery: without this, anyone could bind their own Discord account to a hash they
	 * merely typed and then "recover" the account it belongs to. The circularity is the whole reason the
	 * research doc made this a prerequisite rather than a follow-up.
	 *
	 * <p>Only applied to accounts that have a credential. One that has never enrolled has nothing to
	 * authenticate against, and its links are marked unverified anyway, so they buy no recovery.
	 */
	private boolean ownsAccountForLinking(Subscriber sub, Inbound in, String frame) {
		if (!actingOnOwnAccount(sub, in, frame)) {
			return false;
		}
		if (!auth.enrolmentEnabled() || !auth.hasActiveCredential(in.accountHash())) {
			return true;
		}
		if (sub.authenticated && sub.accountHash != null && sub.accountHash.equals(in.accountHash())) {
			return true;
		}
		log.warn("Refused {}: session={} is not signed in as account {}",
			frame, sub.session.id(), in.accountHash());
		return false;
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
		if (!ownsAccountForLinking(sub, in, "startDiscordLink")) {
			sendError(sub, null, "sign in on this device first");
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
		// Same bar as linking: unlinking is how an attacker would clear a victim's verified link before
		// planting their own, so leaving it on the weaker check would route straight around that one.
		if (!ownsAccountForLinking(sub, in, "unlinkDiscord")) {
			sendError(sub, null, "sign in on this device first");
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
		if (!actingOnOwnAccount(sub, in, "getDiscordLink")) {
			sendError(sub, null, "not your account");
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
		if (!actingOnOwnAccount(sub, in, "setBadgeVisibility")) {
			sendError(sub, null, "not your account");
			return;
		}
		badges.setBadgesHidden(in.accountHash(), !in.visible());
		log.info("Badge visibility for accountHash {} -> {}", in.accountHash(), in.visible());
		DiscordLinkService.Link link = discordLinks.getByAccountHash(in.accountHash()).orElse(null);
		send(sub, Outbound.discordLink(version.get(), in.accountHash(),
			link == null ? null : link.discordId(), link == null ? null : link.username(), in.visible()));
	}

	private List<String> linkedDiscordIds(Advertisement ad) {
		List<String> ids = new java.util.ArrayList<>();
		if (ad.getMembers() != null) {
			for (net.osparty.api.model.Member member : ad.getMembers()) {
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
		Advertisement ad = store.findById(id).orElse(null);
		if (ad == null || ad.getDiscordChannelId() == null) {
			sendError(sub, id, "no channel");
			return;
		}
		boolean inParty = ad.getMembers() != null && ad.getMembers().stream()
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
		if (!voice.grantAccess(ad.getDiscordChannelId(), discordId)) {
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
		String sessionId = sub.session.id();
		// An authenticated session already knows which account it is and cannot be told otherwise. Its name
		// is still taken from the frame below: the account is proved, the display name is not, and a name is
		// only ever a routing label here.
		if (in.accountHash() != null && in.accountHash() != 0 && !sub.authenticated) {
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
			if (mayClaimName(sub, name, sessionId)) {
				sessionByName.put(name, sessionId);
			}
		}
		maybeEnrol(sub, in);
	}

	/**
	 * Issue this client a credential the first time it tells us an account we have no credential for.
	 *
	 * <p>This is the trust-on-first-use moment, and it is the only point in the scheme where an account hash
	 * is taken on faith. Everything after it runs on the credential. The token is sent once, in this frame,
	 * and never again -- we keep only its digest, so a client that loses it has to enrol afresh rather than
	 * ask for it back.
	 *
	 * <p>Silent when enrolment is off, which is how it ships: a client that gets no {@code authIssued} is
	 * expected to carry on unauthenticated, exactly as every client from before this existed already does.
	 *
	 * <p>If the account already has a credential this cannot enrol, and says so with {@link AuthFailed}
	 * rather than silently -- along with which of the ways back in are actually open, so the client can
	 * offer those and only those.
	 *
	 * <p><b>Once per session per account.</b> {@code identify} is re-sent on every reconnect, and a client
	 * that cannot enrol reconnects on every world hop and every network blip. Answering each one used to put
	 * a modal prompt on the user's screen and a fresh six-digit code on their other machine's, over and over,
	 * for a problem that had not changed since the first time. The client asks again with {@code retryAuth}
	 * when the user has actually done something about it.
	 */
	private void maybeEnrol(Subscriber sub, Inbound in) {
		if (sub.authenticated || !auth.enrolmentEnabled() || in.accountHash() == null) {
			return;
		}
		if (!sub.authOffered.add(in.accountHash())) {
			return;
		}
		attemptEnrol(sub, in.accountHash());
	}

	private void attemptEnrol(Subscriber sub, Long accountHash) {
		if (auth.hasActiveCredential(accountHash)) {
			// Asked across the cluster, so the answer arrives later than the frame that triggered it. Sending
			// the failure from the callback costs a few hundred milliseconds before a dialog the user is not
			// waiting on appears, which is a better trade than answering "no other device" without looking.
			couplings.anyDeviceOnline(accountHash)
				.whenComplete((online, error) -> sendAuthFailed(sub, accountHash, Boolean.TRUE.equals(online)));
			return;
		}
		auth.enrol(accountHash, sub.clientIp, sub.deviceLabel).ifPresent(issued -> {
			sub.authenticated = true;
			sub.accountHash = issued.accountHash();
			sendAuthIssued(sub, issued);
		});
	}

	/**
	 * Mint a code and show it on this account's other machines, because somebody asked for one.
	 *
	 * <p><b>Only ever on request.</b> This used to happen automatically the moment a sign-in failed, which
	 * meant naming an account hash was enough to put a modal on the real owner's screen -- a stranger could
	 * do it, repeatedly, and the owner had no way to tell it apart from their own second machine. Now nothing
	 * is displayed anywhere until a person has chosen to send one, and {@code authFailed} only reports
	 * whether the route exists.
	 *
	 * <p>The code goes to those machines and to nothing else. The challenger is told how many were reached
	 * and no more: reading it off the other screen is the whole test, so sending the code here would answer
	 * its own question.
	 */
	private void handleRequestCouplingCode(Subscriber sub, Inbound in) {
		if (in.accountHash() == null) {
			sendError(sub, null, "missing accountHash");
			return;
		}
		if (sub.authenticated || !auth.hasActiveCredential(in.accountHash())) {
			// Nothing to couple to: either this connection is already signed in, or the account has no other
			// machine for a code to come from.
			sendCouplingCodeSent(sub, in.accountHash(), 0);
			return;
		}
		Optional<String> code = auth.generateCouplingCode(in.accountHash(), sub.session.id());
		if (code.isEmpty()) {
			// A code is already pending. When it is this session's own -- which is what asking twice looks
			// like -- it is still on the other screen and still spendable, so reporting nothing sent would
			// talk the user out of the one thing already working. Only a different challenger's request
			// really blocks this one.
			sendCouplingCodeSent(sub, in.accountHash(),
				auth.hasPendingCouplingFor(in.accountHash(), sub.session.id()) ? 1 : 0);
			return;
		}
		couplings.deliverCode(in.accountHash(), code.get()).whenComplete((reached, error) -> {
			int shown = reached == null ? 0 : reached;
			if (shown == 0) {
				// Nothing online to read it off after all. Dropped rather than left pending, or it blocks the
				// next attempt for its whole lifetime -- and this is the case recovery codes exist for.
				auth.cancelCoupling(in.accountHash());
			}
			sendCouplingCodeSent(sub, in.accountHash(), shown);
		});
	}

	private void sendCouplingCodeSent(Subscriber sub, Long accountHash, int reached) {
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(new CouplingCodeSent(
				"couplingCodeSent", accountHash, reached))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver a coupling-code ack to session {}: {}",
				sub.session.id(), e.toString());
		}
	}

	/**
	 * Tell a client that it could not be signed in, and what it can do about it.
	 *
	 * <p>Every route this frame advertises is one the server has just confirmed is open, so the client never
	 * offers a door that turns out to be locked. Carries no code and no Discord identity: it goes to a
	 * connection that has proved nothing, and naming an account hash must not be a way to learn things.
	 */
	private void sendAuthFailed(Subscriber sub, Long accountHash, boolean coupling) {
		boolean recoveryCodes = auth.recoveryCodesRemaining(accountHash) > 0;
		boolean discord = discordRecovery != null && discordRecovery.available(accountHash);
		if (!coupling && !recoveryCodes && !discord) {
			log.warn("Account {} could not enrol and has no recovery route", accountHash);
		}
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(new AuthFailed(
				"authFailed", accountHash, "already-enrolled", coupling, recoveryCodes, discord))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver sign-in failure to session {}: {}",
				sub.session.id(), e.toString());
		}
	}

	/**
	 * Try signing in again, because the user has done something that might have changed the answer -- opened
	 * OSParty on the other machine, most often.
	 *
	 * <p>Clears the once-per-session mark {@link #maybeEnrol} sets, so this is the deliberate way past it. It
	 * is a user action rather than a timer, which is what keeps it from becoming the reconnect spam again.
	 */
	private void handleRetryAuth(Subscriber sub, Inbound in) {
		if (sub.authenticated || !auth.enrolmentEnabled() || in.accountHash() == null) {
			return;
		}
		sub.authOffered.remove(in.accountHash());
		maybeEnrol(sub, in);
	}

	/**
	 * Show the code on this node's connections that already hold a credential for this account.
	 *
	 * <p>Authenticated sessions only. {@code Subscriber.accountHash} is also set from an {@code identify}
	 * frame, which anybody can send naming anybody -- so matching on it alone would deliver the code to a
	 * session that merely claimed the account, which is exactly the session the code exists to keep out.
	 *
	 * <p>This node's connections only. It used to be the whole of coupling, which quietly made the feature
	 * a coin toss: nothing pins a person's two machines to one replica, so with three of them the sweep
	 * missed the other machine about two times in three. {@link CouplingBus} is what asks the rest of the
	 * cluster the same question, and this is the half it calls back into here.
	 *
	 * @return how many machines were shown the code; zero means nobody here can read it.
	 */
	private int sendCouplingCodeLocally(Long accountHash, String code) {
		int reached = 0;
		for (Subscriber s : subscribers.values()) {
			if (!s.authenticated || !accountHash.equals(s.accountHash)) {
				continue;
			}
			try {
				sendRaw(s, new Frame(mapper.writeValueAsString(new CouplingCode(
					"couplingCode", accountHash, code))));
				reached++;
			}
			catch (Exception e) {
				log.warn("Failed to deliver coupling code to session {}: {}",
					s.session.id(), e.toString());
			}
		}
		return reached;
	}

	/**
	 * Whether this node holds a signed-in connection for an account.
	 *
	 * <p>Same authentication rule and the same reason as {@link #sendCouplingCodeLocally}: a session that
	 * merely claimed the account is not one of its devices, and counting it would let anybody make the
	 * coupling route look available by naming a hash.
	 */
	private boolean hasLocalDeviceOnline(long accountHash) {
		for (Subscriber s : subscribers.values()) {
			if (s.authenticated && Long.valueOf(accountHash).equals(s.accountHash)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Handle a coupling code confirmation. The challenger presents the code it was told to enter.
	 */
	private void handleCouplingConfirm(Subscriber sub, Inbound in) {
		if (in.accountHash() == null || in.code() == null) {
			sendError(sub, null, "missing accountHash or code");
			return;
		}
		Optional<net.osparty.api.service.AccountAuthService.Issued> result = auth.validateCouplingCode(
			in.accountHash(), in.code(), sub.session.id(), sub.clientIp, sub.deviceLabel);
		if (result.isPresent()) {
			sub.authenticated = true;
			sub.accountHash = result.get().accountHash();
			sendAuthIssued(sub, result.get());
			sendCouplingResult(sub, in.accountHash(), true);
			// The machines that were already on this account keep their credentials -- coupling adds one.
			// They are told so the user sees, on the screen they read the code off, that it was used.
			notifyIncumbentOfCoupling(in.accountHash(), sub.session.id());
		} else {
			sendCouplingResult(sub, in.accountHash(), false);
		}
	}

	// --- Recovery ---

	/**
	 * Redeem a recovery code: the path for when the other machine is not offline but gone.
	 *
	 * <p>Capped per connection. Eighty bits is far out of reach of guessing, so this is not what stops a
	 * brute force -- it stops a broken or malicious client sitting in a loop, and it costs a legitimate user
	 * nothing, since anyone who has mistyped a code ten times has a different problem.
	 */
	private void handleRecoveryConfirm(Subscriber sub, Inbound in) {
		if (in.accountHash() == null || in.code() == null) {
			sendRecoveryResult(sub, false, "missing accountHash or code");
			return;
		}
		if (sub.authenticated) {
			// Already signed in on this account; spending a code would only burn one for nothing.
			sendRecoveryResult(sub, false, "already signed in");
			return;
		}
		if (sub.recoveryAttempts.incrementAndGet() > MAX_RECOVERY_ATTEMPTS_PER_SESSION) {
			log.warn("Session {} over the recovery attempt ceiling", sub.session.id());
			sendRecoveryResult(sub, false, "too many attempts, reconnect and try again");
			return;
		}
		Optional<net.osparty.api.service.AccountAuthService.Issued> issued =
			auth.redeemRecoveryCode(in.accountHash(), in.code(), sub.clientIp, sub.deviceLabel);
		if (issued.isEmpty()) {
			sendRecoveryResult(sub, false, "that code isn't valid");
			return;
		}
		signIn(sub, issued.get());
		sendRecoveryResult(sub, true, null);
	}

	/**
	 * Mint a fresh set of codes for the caller's own account, replacing any it has left.
	 *
	 * <p>Authenticated only, and on this connection's own account -- there is deliberately no way to ask for
	 * another account's codes by naming its hash, because that would be a way to take the account.
	 */
	private void handleIssueRecoveryCodes(Subscriber sub) {
		if (!sub.authenticated) {
			sendError(sub, null, "not authenticated");
			return;
		}
		List<String> codes = auth.issueRecoveryCodes(sub.accountHash);
		sendRecoveryCodes(sub, codes, codes.size());
	}

	/** How many codes the caller's account has left, for the device list. Never the codes themselves. */
	private void handleRecoveryStatus(Subscriber sub) {
		if (!sub.authenticated) {
			sendError(sub, null, "not authenticated");
			return;
		}
		sendRecoveryCodes(sub, null, auth.recoveryCodesRemaining(sub.accountHash));
	}

	/**
	 * Begin Discord recovery: hand back a URL for the browser and a ticket for this socket to poll with.
	 *
	 * <p>Starting one proves nothing and grants nothing. What settles it is whether the person who follows
	 * the URL can sign in to the Discord account this OSRS account was linked to from a signed-in session.
	 */
	private void handleStartDiscordRecovery(Subscriber sub, Inbound in) {
		if (in.accountHash() == null) {
			sendError(sub, null, "missing accountHash");
			return;
		}
		if (discordRecovery == null) {
			sendError(sub, null, "recovery unavailable");
			return;
		}
		Optional<net.osparty.api.service.DiscordRecoveryService.Started> started =
			discordRecovery.begin(in.accountHash());
		if (started.isEmpty()) {
			sendError(sub, null, "no verified Discord link");
			return;
		}
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(new DiscordRecoveryUrl(
				"discordRecoveryUrl", started.get().url(), started.get().ticket()))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver a recovery URL to session {}: {}", sub.session.id(), e.toString());
		}
	}

	/**
	 * Has the browser half finished yet?
	 *
	 * <p>Polled rather than pushed because the two halves land on different pods: the callback is an HTTP
	 * request to whichever replica the ingress picked, and it has no way to reach the socket. The ticket is
	 * what ties them together, and it is the client's to hold -- so a reconnect mid-flow does not lose the
	 * authorisation the user has already given.
	 *
	 * <p>A poll that finds nothing is the normal case and answers {@code pending}, not a failure.
	 */
	private void handleDiscordRecoveryPoll(Subscriber sub, Inbound in) {
		if (discordRecovery == null || in.ticket() == null || in.ticket().isBlank()) {
			sendRecoveryResult(sub, false, "recovery unavailable");
			return;
		}
		if (sub.authenticated) {
			sendRecoveryResult(sub, true, null);
			return;
		}
		Optional<Long> approved = discordRecovery.claim(in.ticket());
		if (approved.isEmpty()) {
			sendRecoveryPending(sub);
			return;
		}
		Optional<net.osparty.api.service.AccountAuthService.Issued> issued = auth.enrolVerified(
			approved.get(), sub.clientIp, sub.deviceLabel, "Discord");
		if (issued.isEmpty()) {
			// The approval is spent by now, so say plainly that it has to be started again rather than
			// leaving the client polling a ticket that will never resolve.
			sendRecoveryResult(sub, false, "couldn't add this device, try again");
			return;
		}
		signIn(sub, issued.get());
		sendRecoveryResult(sub, true, null);
	}

	/**
	 * Adopt a freshly issued credential onto this connection.
	 *
	 * <p>The account moves onto the session as <em>authenticated</em>, which is what stops a later
	 * {@code identify} moving it somewhere else, and it is indexed for invite routing the same way an
	 * authenticated handshake does at {@link #onOpen}.
	 */
	private void signIn(Subscriber sub, net.osparty.api.service.AccountAuthService.Issued issued) {
		// A Discord recovery resolves its account from the ticket, not from the frame, so the account this
		// session ends up as need not be the one it claimed on the way in. Drop the old index entry or that
		// claim keeps receiving invites for an account this connection is not.
		if (sub.accountHash != null && !sub.accountHash.equals(issued.accountHash())) {
			sessionByAccount.remove(sub.accountHash, sub.session.id());
		}
		sub.authenticated = true;
		sub.accountHash = issued.accountHash();
		sessionByAccount.put(issued.accountHash(), sub.session.id());
		sendAuthIssued(sub, issued);
	}

	private void sendRecoveryCodes(Subscriber sub, List<String> codes, int remaining) {
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(
				new RecoveryCodes("recoveryCodes", codes, remaining))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver recovery codes to session {}: {}", sub.session.id(), e.toString());
		}
	}

	private void sendRecoveryResult(Subscriber sub, boolean success, String detail) {
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(
				new RecoveryResult("recoveryResult", success, false, detail))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver a recovery result to session {}: {}", sub.session.id(), e.toString());
		}
	}

	private void sendRecoveryPending(Subscriber sub) {
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(
				new RecoveryResult("recoveryResult", false, true, null))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver a recovery poll to session {}: {}", sub.session.id(), e.toString());
		}
	}

	/**
	 * List the machines currently entitled to speak for the caller's own account.
	 *
	 * <p>Requires {@code sub.authenticated}: managing devices is something an account does to itself, and
	 * the only account this connection can act as is the one its own credential proved. There is
	 * deliberately no path that lists another account's devices by naming a hash.
	 */
	private void handleListDevices(Subscriber sub) {
		if (!sub.authenticated) {
			sendError(sub, null, "not authenticated");
			return;
		}
		List<net.osparty.api.repository.AccountCredentialRepository.Credential> devices =
			auth.devices(sub.accountHash);
		List<DeviceSummary> summaries = new java.util.ArrayList<>(devices.size());
		for (net.osparty.api.repository.AccountCredentialRepository.Credential d : devices) {
			summaries.add(new DeviceSummary(d.tokenHash(), d.label(),
				d.issuedAt() == null ? 0 : d.issuedAt().toEpochMilli(),
				d.lastSeenAt() == null ? 0 : d.lastSeenAt().toEpochMilli()));
		}
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(new Devices("devices", summaries))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver device list to session {}: {}", sub.session.id(), e.toString());
		}
	}

	/**
	 * Withdraw one of the caller's own devices by id. This is the only way a lost or stolen device is ever
	 * removed -- {@link net.osparty.api.service.AccountAuthService#revoke} needs the plaintext token, which
	 * by definition only the lost device still holds.
	 */
	private void handleRevokeDevice(Subscriber sub, Inbound in) {
		if (!sub.authenticated) {
			sendError(sub, null, "not authenticated");
			return;
		}
		if (in.deviceId() == null || in.deviceId().isBlank()) {
			sendError(sub, null, "missing deviceId");
			return;
		}
		boolean revoked = auth.revokeDevice(sub.accountHash, in.deviceId());
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(
				new DeviceRevoked("deviceRevoked", in.deviceId(), revoked))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver device revocation result to session {}: {}",
				sub.session.id(), e.toString());
		}
	}

	/**
	 * Rename one of the caller's own devices. Purely cosmetic -- the label is never read for anything but
	 * display, so there is nothing here to validate beyond ownership, which {@link AccountAuthService#renameDevice}
	 * already enforces the same way {@code revokeDevice} does.
	 */
	private void handleRenameDevice(Subscriber sub, Inbound in) {
		if (!sub.authenticated) {
			sendError(sub, null, "not authenticated");
			return;
		}
		if (in.deviceId() == null || in.deviceId().isBlank()) {
			sendError(sub, null, "missing deviceId");
			return;
		}
		boolean renamed = auth.renameDevice(sub.accountHash, in.deviceId(), in.label());
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(
				new DeviceRenamed("deviceRenamed", in.deviceId(), renamed))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver device rename result to session {}: {}",
				sub.session.id(), e.toString());
		}
	}

	/** One row of {@link Devices}. {@code id} is the token's stored digest -- see revokeDevice's doc. */
	record DeviceSummary(String id, String label, long issuedAt, long lastSeenAt) {
	}

	record Devices(String type, List<DeviceSummary> devices) {
	}

	record DeviceRevoked(String type, String deviceId, boolean success) {
	}

	record DeviceRenamed(String type, String deviceId, boolean success) {
	}

	private void sendCouplingResult(Subscriber sub, Long accountHash, boolean success) {
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(new CouplingResult(
				"couplingResult", accountHash, success))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver coupling result to session {}: {}",
				sub.session.id(), e.toString());
		}
	}

	/**
	 * Tell the machines already on this account that another one has just joined it.
	 *
	 * <p>Never the session that did the joining: by this point the challenger is authenticated on the same
	 * account, so an unfiltered sweep reaches it too and it would be told about itself.
	 *
	 * <p>This is a notice, not a revocation -- nothing here loses its credential. It exists so that a code
	 * being spent is visible on the screen it was displayed on, which is the one place someone who did not
	 * expect it would notice.
	 */
	private void notifyIncumbentOfCoupling(Long accountHash, String exceptSessionId) {
		subscribers.values().stream()
			.filter(s -> s.authenticated && accountHash.equals(s.accountHash)
				&& !s.session.id().equals(exceptSessionId))
			.forEach(s -> {
				try {
					sendRaw(s, new Frame(mapper.writeValueAsString(new CouplingAccepted(
						"couplingAccepted", accountHash))));
				}
				catch (Exception e) {
					log.warn("Failed to deliver coupling notice to session {}: {}",
						s.session.id(), e.toString());
				}
			});
	}

	/** The one delivery of a freshly minted token, alongside the public id it will be known by. */
	private void sendAuthIssued(Subscriber sub, net.osparty.api.service.AccountAuthService.Issued issued) {
		try {
			sendRaw(sub, new Frame(mapper.writeValueAsString(new AuthIssued(
				"authIssued", issued.token(), playerIds.of(issued.accountHash()), issued.firstDevice(),
				issued.recoveryCodes().isEmpty() ? null : issued.recoveryCodes()))));
		}
		catch (Exception e) {
			log.warn("Failed to deliver an issued credential to session {}: {}",
				sub.session.id(), e.toString());
		}
	}

	/**
	 * Sent once, when a credential is minted. Its own frame rather than a variant of {@link Outbound} so the
	 * token does not become a nullable field on every other frame this service sends.
	 *
	 * @param codes the one-time recovery codes, present only on an account's first credential -- see
	 *     {@link net.osparty.api.service.AccountAuthService.Issued}. Absent on every later enrolment.
	 *     <p><b>Named {@code codes}, not {@code recoveryCodes}, and it matters.</b> The plugin deserialises
	 *     every frame into one flat class, where {@code recoveryCodes} is already the boolean on
	 *     {@link AuthFailed} saying that route is open. Sending a list under that name made Gson bind an
	 *     array to a Boolean, throw, and drop the whole frame -- so a first sign-in stored no credential and
	 *     showed no codes, while the server had happily written the row. Same name as the
	 *     {@link RecoveryCodes} frame's list, which is what a client reads it into.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AuthIssued(String type, String token,
		String playerId, boolean firstDevice, List<String> codes) {
	}

	/**
	 * Sent to a client that could not be signed in, naming the ways back in that are actually open.
	 *
	 * <p>Replaces the pair of frames that used to say "type a code" and "there was nobody to give you one".
	 * Both described a coupling attempt rather than the thing that had happened, which is that this device
	 * is not signed in -- so a user who had simply lost their credential was shown a prompt about a code
	 * that had never existed, on every reconnect, and told nothing about the two paths that would have
	 * worked. The flags are all server-confirmed: a route offered here is one that will answer.
	 *
	 * @param coupling another of this account's machines is signed in somewhere, so a code <em>could</em> be
	 *     sent to it. Deliberately not "a code is waiting": nothing is minted or displayed until a person
	 *     asks with {@code requestCouplingCode}, because otherwise naming an account hash was enough to put
	 *     a dialog in front of whoever really owns it
	 * @param recoveryCodes this account has unspent recovery codes
	 * @param discord this account has a Discord link made by a signed-in session
	 */
	record AuthFailed(String type, Long accountHash, String reason,
		boolean coupling, boolean recoveryCodes, boolean discord) {
	}

	/**
	 * How many of the account's machines were shown the code just asked for.
	 *
	 * <p>Zero is a real answer, not an error: presence was checked before the request and the last device
	 * can go offline in between. The client needs to tell that apart from a code sitting unread.
	 */
	record CouplingCodeSent(String type, Long accountHash, int reached) {
	}

	/**
	 * The codes themselves on issue, or only how many are left on a status check.
	 *
	 * @param codes the plaintext, which exists here and nowhere else afterwards -- null when this is a
	 *     status reply rather than an issue.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record RecoveryCodes(String type, List<String> codes, int remaining) {
	}

	/**
	 * How a recovery attempt went.
	 *
	 * @param pending set when a Discord recovery poll found no answer yet, which is the ordinary case and
	 *     not a failure -- without it the client cannot tell "not finished" from "refused".
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record RecoveryResult(String type, boolean success, boolean pending, String detail) {
	}

	/** Where to send the browser, and the secret this socket polls with. See {@code DiscordRecoveryService}. */
	record DiscordRecoveryUrl(String type, String url, String ticket) {
	}

	/** Sent to the incumbent: here is the code to display. */
	record CouplingCode(String type, Long accountHash, String code) {
	}

	/** Sent to the challenger after it presents a code: success or failure. */
	record CouplingResult(String type, Long accountHash, boolean success) {
	}

	/** Sent to the incumbent after the challenger succeeds: your credential has been revoked. */
	/** Sent to the machines already on an account when another one couples to it. Nothing is lost. */
	record CouplingAccepted(String type, Long accountHash) {
	}

	/**
	 * Whether this connection may become the invite destination for {@code name}.
	 *
	 * <p>The index used to be a plain overwrite, so claiming a name that somebody else was already connected
	 * under redirected their invites to the claimant -- no party membership needed, and nothing the target
	 * could notice. Identity is still self-asserted, so this cannot be settled properly until the socket is
	 * authenticated; what it can do is stop the claim being free.
	 *
	 * <p>The incumbent keeps the name unless the claimant reports the same account, which is what a genuine
	 * reconnect does -- a new session, the same player. The half-open connection problem is why this is not
	 * simply "refuse while the incumbent is open": a client killed outright leaves a socket that still reads
	 * as connected, and its owner has to be able to take its own name back. An incumbent that reported no
	 * account at all is not evidence of anything and does not block the claim.
	 */
	private boolean mayClaimName(Subscriber sub, String name, String sessionId) {
		String incumbentId = sessionByName.get(name);
		if (incumbentId == null || incumbentId.equals(sessionId)) {
			return true;
		}
		Subscriber incumbent = subscribers.get(incumbentId);
		if (incumbent == null || incumbent.accountHash == null) {
			return true;
		}
		if (incumbent.accountHash.equals(sub.accountHash)) {
			return true;
		}
		log.info("Refused name claim: session={} name={} already held by session={} on another account",
			sessionId, name, incumbentId);
		return false;
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
		Advertisement ad = store.findById(id).orElse(null);
		if (ad == null) {
			// The party vanished (TTL/disband) between opening the menu and inviting; report as not delivered.
			send(sub, Outbound.inviteAck(version.get(), in.target(), false));
			return;
		}
		if (!senderInAdvertisement(ad, in)) {
			sendError(sub, id, "not in party");
			return;
		}
		if (memberByName(ad, target) != null) {
			// They joined between the menu opening and the invite; nothing to deliver.
			send(sub, Outbound.inviteAck(version.get(), in.target(), false));
			return;
		}
		// Hiding a banned ad from the board is pointless if its host can still push it to arbitrary
		// players by name. Keyed on the party's host rather than the sender, so a banned host
		// cannot route around it by asking a party-mate to send the invites for them. The ack
		// claims delivery, because an invite that visibly fails is a ban notification.
		if (bans.isHidden(ad)) {
			log.info("WS invite suppressed (host banned): party={} target={}", id, target);
			send(sub, Outbound.inviteAck(version.get(), in.target(), true));
			return;
		}
		// Promise the seat before dispatching the invite. The live-party side auto-admits on this rather than
		// on the joiner's own `invited` flag, and the room it will ask about is keyed by the ad's passphrase
		// -- which this is the only side to hold. Granted even if delivery then fails: an invite the target
		// heard about by other means is still one we authorised.
		admissions.grant(ad.getPassphrase(), target);
		String from = (in.name() == null || in.name().isBlank()) ? ad.getHost() : in.name();
		String frame;
		try {
			frame = mapper.writeValueAsString(Outbound.invited(version.get(), enriched(ad), from));
		}
		catch (Exception e) {
			sendError(sub, id, "invite failed");
			return;
		}
		// The target may be connected to any replica; the bus finds and delivers it cluster-wide.
		inviteBus.dispatch(target, frame).whenComplete((delivered, error) -> {
			log.info("WS invite: session={} party={} target={} delivered={}",
				sub.session.id(), id, target, delivered);
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
		if (sub.reportedAdIds.contains(id)) {
			reportSuppressed("duplicate-session");
			return;
		}
		if (sub.reportedAdIds.size() >= reportsPerSession) {
			reportSuppressed("session-cap");
			return;
		}
		Advertisement ad = store.findById(id).orElse(null);
		if (ad == null) {
			reportSuppressed("unknown-party");
			return;
		}
		if (isOwnAdvertisement(sub, ad)) {
			reportSuppressed("self-report");
			return;
		}
		if (bans.isHidden(ad)) {
			// Already handled; re-reporting a banned host would just re-notify moderators.
			reportSuppressed("already-banned");
			return;
		}
		String clientIp = sub.clientIp;
		if (false) {
		}
		if (!rateLimiter.withinIpBudget(clientIp)) {
			reportSuppressed("ip-cap");
			return;
		}
		sub.reportedAdIds.add(id);
		reportsReceived.increment();

		String normalizedHost = normalizeName(ad.getHost());
		String fingerprint = sub.accountHash != null ? "a:" + sub.accountHash
			: (sub.name != null ? "n:" + sub.name : "s:" + sub.session.id());
		ReportRateLimiter.Decision decision =
			rateLimiter.evaluate(id, normalizedHost == null ? "" : normalizedHost, fingerprint);

		long reportId;
		try {
			reportId = reports.insert(buildReport(sub, ad, normalizedHost, rateLimiter.hash(clientIp)));
		}
		catch (Exception e) {
			log.warn("Failed to record report for party {}: {}", id, e.toString());
			return;
		}
		log.info("WS report: session={} party={} host={} distinctReporters={} notify={} reason={}",
			sub.session.id(), id, ad.getHost(), decision.distinctReporters(),
			decision.shouldNotify(), decision.reason());

		if (!decision.shouldNotify()) {
			reportSuppressed(decision.reason());
			return;
		}
		adReports.publish(new net.osparty.api.service.AdReportService.ReviewRequest(
				reportId, ad.getHost(), ad.getHostAccountHash(), ad.getActivity(),
				ad.getDescription(), ad.getWorld(), ad.getCapacity(), ad.getSize(),
				ad.getInviteCode(), sub.name, decision.distinctReporters()))
			.ifPresent(posted -> {
				reports.markNotified(reportId, posted.channelId(), posted.messageId());
				reportsNotified.increment();
			});
	}

	private net.osparty.api.model.AdReport buildReport(Subscriber sub, Advertisement ad,
		String normalizedHost, String ipHash) {
		net.osparty.api.model.AdReport report = new net.osparty.api.model.AdReport();
		report.setPartyId(ad.getId());
		report.setHostName(normalizedHost == null ? "" : normalizedHost);
		report.setHostNameRaw(ad.getHost());
		report.setHostAccountHash(ad.getHostAccountHash() == 0 ? null : ad.getHostAccountHash());
		report.setActivity(ad.getActivity());
		report.setDescription(ad.getDescription());
		report.setWorld(ad.getWorld());
		report.setCapacity(ad.getCapacity());
		report.setPartySize(ad.getSize());
		report.setInviteCode(ad.getInviteCode());
		report.setAdSnapshot(snapshotJson(ad));
		report.setReporterName(sub.name);
		report.setReporterAccountHash(sub.accountHash);
		report.setReporterSessionId(sub.session.id());
		report.setReporterIpHash(ipHash);
		return report;
	}

	/** The advertisement verbatim: the only surviving evidence once the 90s ad TTL elapses. */
	private String snapshotJson(Advertisement ad) {
		try {
			return mapper.writeValueAsString(ad);
		}
		catch (Exception e) {
			log.warn("Failed to serialise ad snapshot for party {}: {}", ad.getId(), e.toString());
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
		sendRaw(sub, new Frame(frameJson));
		return true;
	}

	/** Whether the invite sender is the party host or an admitted member (by name or accountHash). */
	private static boolean senderInAdvertisement(Advertisement ad, Inbound in) {
		String senderName = normalizeName(in.name());
		if (senderName != null && senderName.equals(normalizeName(ad.getHost()))) {
			return true;
		}
		if (ad.getMembers() == null) {
			return false;
		}
		if (in.accountHash() != null && in.accountHash() != 0) {
			long hash = in.accountHash();
			if (ad.getMembers().stream().anyMatch(m -> m.getAccountHash() == hash)) {
				return true;
			}
		}
		return senderName != null && memberByName(ad, senderName) != null;
	}

	private static net.osparty.api.model.Member memberByName(Advertisement ad, String normalizedName) {
		if (ad.getMembers() == null) {
			return null;
		}
		return ad.getMembers().stream()
			.filter(m -> normalizedName.equals(normalizeName(m.getName())))
			.findFirst().orElse(null);
	}

	/**
	 * Normalise an OSRS name for identity matching, returning null rather than an empty string when
	 * there is no usable name. Delegates to {@link AdvertisementFactory#normalizeHost} so that socket
	 * identity, the Redis {@code partyhost:} index and the ban tables all key on one identity space
	 * -- two subtly different normalisers here would mean a ban that matches the board but not a
	 * lookup, or vice versa.
	 */
	private static String normalizeName(String name) {
		if (name == null) {
			return null;
		}
		String normalized = AdvertisementFactory.normalizeHost(name);
		return normalized.isEmpty() ? null : normalized;
	}

	private void forgetIdentity(Subscriber sub) {
		if (sub == null) {
			return;
		}
		if (sub.accountHash != null) {
			sessionByAccount.remove(sub.accountHash, sub.session.id());
		}
		if (sub.name != null) {
			sessionByName.remove(sub.name, sub.session.id());
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
		Advertisement ad = store.findById(id).orElse(null);
		if (ad == null || ad.getDiscordChannelId() == null) {
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
			id, in.accountHash(), discordId, ad.getDiscordChannelId());
		voice.revokeAccess(ad.getDiscordChannelId(), discordId);
		voice.disconnectFromChannel(ad.getDiscordChannelId(), discordId);
	}

	private boolean authorizeWrite(Subscriber sub, String id, String key) {
		if (id.equals(hostedAdBySession.get(sub.session.id()))) {
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
		bind(sub.session.id(), id);
		return true;
	}

	private void bind(String sessionId, String adId) {
		hostedAdBySession.put(sessionId, adId);
		String previous = ownerSession.put(adId, sessionId);
		if (previous != null && !previous.equals(sessionId)) {
			hostedAdBySession.remove(previous, adId);
		}
	}

	private void unbind(String sessionId) {
		String adId = hostedAdBySession.remove(sessionId);
		if (adId != null) {
			ownerSession.remove(adId, sessionId);
		}
	}

	/** The connection went away. */
	public void onClose(String sessionId, String status) {
		forgetIdentity(subscribers.remove(sessionId));
		unbind(sessionId);
		log.info("WS closed: session={} status={} (subscribers={})",
			sessionId, status, subscribers.size());
	}

	@Scheduled(fixedDelayString = "${app.ws.presence-interval-ms:5000}")
	public void broadcastPresence() {
		int online = presence.recordAndTotal(subscribers.size());
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
	public void touchOwnedAds() {
		for (Map.Entry<String, String> entry : hostedAdBySession.entrySet()) {
			Subscriber sub = subscribers.get(entry.getKey());
			if (sub == null || !sub.session.isOpen()) {
				continue;
			}
			if (store.update(entry.getValue(), TTL_TOUCH).isEmpty()) {
				log.info("WS touch: ad {} is gone; notifying host session {}",
					entry.getValue(), entry.getKey());
				send(sub, Outbound.gone(version.get(), entry.getValue()));
				unbind(entry.getKey());
			}
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
	void publishBoard(List<Advertisement> visible, List<Advertisement> hidden,
		Map<String, BoardReconciler.Tombstone> tombstones, long prunedThroughSeq) {
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
		if (since != null && current != null && !hostedAdBySession.containsKey(sub.session.id())
			&& !current.showsOwnHidden(sub, this)) {
			Frame resume = current.resume(sub.activity, since, this);
			if (resume != null) {
				sendRaw(sub, resume);
				return;
			}
		}
		if (current != null && !hostedAdBySession.containsKey(sub.session.id())
			&& !current.showsOwnHidden(sub, this)) {
			Frame frame = current.frame(sub.activity, this);
			if (frame != null) {
				sendRaw(sub, frame);
				return;
			}
		}
		List<Advertisement> all = playerIds.enrichAds(badges.enrichAds(store.list(sub.activity)));
		List<Advertisement> list = new java.util.ArrayList<>(all.size());
		for (Advertisement ad : all) {
			if (!bans.isHidden(ad) || isOwnAdvertisement(sub, ad)) {
				list.add(ad);
			}
		}
		log.debug("WS snapshot -> {} ({} of {} ads)", sub.session.id(), list.size(), all.size());
		send(sub, Outbound.snapshot(version.get(), list, headSeq(list)));
	}

	/** The highest revision among a list of ads — what a client resumes from after a full board. */
	private static Long headSeq(List<Advertisement> ads) {
		long head = 0;
		for (Advertisement ad : ads) {
			head = Math.max(head, ad.getSeq());
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
		private final List<Advertisement> visible;
		private final List<Advertisement> hidden;
		private final Map<String, BoardReconciler.Tombstone> tombstones;
		/** Removals older than this are forgotten, so a client asking from before it needs the whole board. */
		private final long prunedThroughSeq;
		/** activity ("" for the all-activities firehose) -> the frame to send. */
		private final Map<String, Frame> frames = new ConcurrentHashMap<>();

		Board(long version, List<Advertisement> visible, List<Advertisement> hidden,
			Map<String, BoardReconciler.Tombstone> tombstones, long prunedThroughSeq) {
			this.version = version;
			this.visible = visible;
			this.hidden = hidden;
			this.tombstones = tombstones;
			this.prunedThroughSeq = prunedThroughSeq;
		}

		/** The highest revision anything on this board carries — what a client should resume from next. */
		long headSeq() {
			long head = prunedThroughSeq;
			for (Advertisement ad : visible) {
				head = Math.max(head, ad.getSeq());
			}
			for (BoardReconciler.Tombstone stone : tombstones.values()) {
				head = Math.max(head, stone.seq());
			}
			return head;
		}

		/**
		 * What changed since {@code since}, for a client that still holds the rest, or null if it has been
		 * away too long to be caught up and needs the board itself.
		 */
		Frame resume(String activity, long since, BoardBroadcaster owner) {
			if (since <= 0 || since <= prunedThroughSeq) {
				return null;
			}
			List<Advertisement> created = new java.util.ArrayList<>();
			for (Advertisement ad : visible) {
				if (ad.getSeq() > since && matches(activity, ad.getActivity())) {
					created.add(ad);
				}
			}
			List<String> removed = new java.util.ArrayList<>();
			for (Map.Entry<String, BoardReconciler.Tombstone> entry : tombstones.entrySet()) {
				if (entry.getValue().seq() > since && matches(activity, entry.getValue().activity())) {
					removed.add(entry.getKey());
				}
			}
			// An empty resume is still an answer: it tells the client its board is current.
			Batch batch = new Batch("batch", version, created.isEmpty() ? null : created, null,
				removed.isEmpty() ? null : removed, headSeq());
			try {
				return new Frame(owner.mapper.writeValueAsString(batch));
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
		boolean showsOwnHidden(Subscriber sub, BoardBroadcaster owner) {
			if (hidden.isEmpty()) {
				return false;
			}
			for (Advertisement ad : hidden) {
				if (owner.isOwnAdvertisement(sub, ad)) {
					return true;
				}
			}
			return false;
		}

		Frame frame(String activity, BoardBroadcaster owner) {
			return frames.computeIfAbsent(activity == null ? "" : activity, key -> {
				List<Advertisement> list = visible;
				if (!key.isEmpty()) {
					list = new java.util.ArrayList<>();
					for (Advertisement ad : visible) {
						if (key.equals(ad.getActivity())) {
							list.add(ad);
						}
					}
				}
				try {
					return new Frame(owner.mapper.writeValueAsString(
						Outbound.snapshot(version, list, headSeq())));
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
	 * {@code hostedAdBySession} is only bound once the client has sent {@code host} or {@code resume}, and
	 * {@code identify} is optional and may arrive after {@code subscribe}. The plugin looks its own
	 * ad up before either is guaranteed -- on the rejoin-after-restart path, and on every heartbeat
	 * from the Party tab, where a null answer makes it disband the party and tell the user so. A
	 * missed self-check there would turn a silent ban into a very loud one.
	 */
	private boolean isOwnAdvertisement(Subscriber sub, Advertisement ad) {
		if (ad == null) {
			return false;
		}
		if (ad.getId().equals(hostedAdBySession.get(sub.session.id()))) {
			return true;
		}
		if (sub.accountHash != null && ad.getHostAccountHash() != 0
			&& sub.accountHash == ad.getHostAccountHash()) {
			return true;
		}
		return sub.name != null && sub.name.equals(normalizeName(ad.getHost()));
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
	void broadcastBatch(List<Advertisement> created, List<AdvertisementDelta> updated, List<RemovedRef> removed) {
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
				String own = hostedAdBySession.get(sub.session.id());
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

	private Frame buildBatch(long v, String activity, List<Advertisement> created, List<AdvertisementDelta> updated,
		List<RemovedRef> removed, String suppress) {
		List<Advertisement> c = filterCreated(created, activity);
		List<AdvertisementDelta> u = filterUpdated(updated, activity);
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
		for (Advertisement ad : c) {
			head = Math.max(head, ad.getSeq());
		}
		Board current = board;
		if (current != null) {
			head = Math.max(head, current.headSeq());
		}
		Batch batch = new Batch("batch", v, c.isEmpty() ? null : c, u.isEmpty() ? null : u,
			r.isEmpty() ? null : r, head > 0 ? head : null);
		try {
			return new Frame(mapper.writeValueAsString(batch));
		}
		catch (Exception e) {
			log.warn("Failed to serialise batch frame", e);
			return null;
		}
	}

	private static List<Advertisement> filterCreated(List<Advertisement> ads, String activity) {
		if (activity == null) {
			return ads;
		}
		List<Advertisement> out = new java.util.ArrayList<>();
		for (Advertisement ad : ads) {
			if (activity.equals(ad.getActivity())) {
				out.add(ad);
			}
		}
		return out;
	}

	private static List<AdvertisementDelta> filterUpdated(List<AdvertisementDelta> deltas, String activity) {
		if (activity == null) {
			return deltas;
		}
		List<AdvertisementDelta> out = new java.util.ArrayList<>();
		for (AdvertisementDelta d : deltas) {
			if (activity.equals(d.activity())) {
				out.add(d);
			}
		}
		return out;
	}

	/** Send a shared frame in whichever form this subscriber asked for. */
	/**
	 * The {@code type} of a serialised frame, for a log line that must not carry the rest of it.
	 *
	 * <p>Read off the front of the JSON rather than parsed: every frame here is built by this class with
	 * {@code type} written first, and re-parsing one on the way out to produce a debug string would be work
	 * done for the log's benefit alone. Anything unrecognisable degrades to {@code ?}, which is all a log
	 * line needs it to do.
	 */
	private static String frameType(String json) {
		if (json == null) {
			return "?";
		}
		int key = json.indexOf("\"type\":\"");
		if (key < 0) {
			return "?";
		}
		int start = key + "\"type\":\"".length();
		int end = json.indexOf('"', start);
		return end < 0 ? "?" : json.substring(start, end);
	}

	private void sendRaw(Subscriber sub, Frame frame) {
		if (!sub.session.isOpen()) {
			return;
		}
		// The type, never the body. Everything that goes out this way is directed at one client and several
		// of them carry a secret -- an issued token, a set of recovery codes, a coupling code -- so the
		// payload cannot go in a log the way {@link #send}'s board frames can. Without even the type, a
		// DEBUG session shows every frame arriving and none of the answers, which is exactly the half that
		// was missing when authIssued was being dropped on the client and nothing anywhere said so.
		if (log.isDebugEnabled()) {
			log.debug("WS -> {} {}", sub.session.id(), frameType(frame.text()));
		}
		try {
			byte[] compressed = sub.compressed ? frame.compressedBytes() : null;
			if (compressed != null) {
				sub.session.send(compressed);
			}
			else {
				sub.session.sendText(frame.text());
			}
		}
		catch (Exception e) {
			log.debug("Dropping subscriber {} after send failure: {}", sub.session.id(), e.toString());
			subscribers.remove(sub.session.id());
			// The seam swallows a failed close; there is nothing useful to do about one anyway.
			sub.session.close();
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
		private final String text;
		private volatile byte[] compressed;

		Frame(String text) {
			this.text = text;
		}

		String text() {
			return text;
		}

		byte[] compressedBytes() {
			byte[] local = compressed;
			if (local != null) {
				return local;
			}
			// Racing threads may both compress; both results are equivalent and the loser is garbage.
			try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
				try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
					gzip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				}
				local = out.toByteArray();
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
		log.debug("WS -> {} {}", sub.session.id(), json);
		try {
			sub.session.sendText(json);
		}
		catch (Exception e) {
			log.debug("Dropping subscriber {} after send failure: {}", sub.session.id(), e.toString());
			subscribers.remove(sub.session.id());
			// The seam swallows a failed close; there is nothing useful to do about one anyway.
			sub.session.close();
		}
	}

	private static final class Subscriber {
		final net.osparty.api.transport.SocketSession session;
		/** Captured at the handshake: the seam carries frames, not the servlet's attribute bag. */
		final String clientIp;
		/** Captured at the handshake, as reported by the client. Used only as an enrolment's initial label. */
		final String deviceLabel;
		volatile boolean subscribed;
		/** Whether this client asked for gzipped binary frames rather than JSON text. */
		volatile boolean compressed;
		volatile String activity;
		// Self-reported identity, mirrored into sessionByAccount/sessionByName for invite routing.
		volatile Long accountHash;
		/**
		 * Whether {@link #accountHash} came from a credential rather than from an {@code identify} frame.
		 * When set, this session's account is settled: identify cannot move it, and the Discord frames act
		 * on it whatever account they name.
		 */
		volatile boolean authenticated;
		volatile String name;
		/**
		 * Accounts this session has already been told it could not sign in as. {@code identify} arrives on
		 * every reconnect, and the answer does not change between them -- so without this the user gets the
		 * same interruption on every world hop. Cleared for one account by {@code retryAuth}.
		 */
		final java.util.Set<Long> authOffered = java.util.concurrent.ConcurrentHashMap.newKeySet();
		/** Recovery codes tried on this connection, against {@link #MAX_RECOVERY_ATTEMPTS_PER_SESSION}. */
		final java.util.concurrent.atomic.AtomicInteger recoveryAttempts =
			new java.util.concurrent.atomic.AtomicInteger();
		/** Advertisements reported on this connection, so each is only reported once per session. */
		final java.util.Set<String> reportedAdIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
		/**
		 * Frame types this session has already been warned about, so a client that sends one on every tick is
		 * reported once rather than filling the log.
		 */
		final java.util.Set<String> warnedTypes = java.util.concurrent.ConcurrentHashMap.newKeySet();

		Subscriber(net.osparty.api.transport.SocketSession session, String clientIp, String deviceLabel) {
			this.clientIp = clientIp;
			this.deviceLabel = deviceLabel;
			this.session = session;
		}

		/** Whether this is the first time {@code type} has gone wrong on this session. */
		boolean firstTime(String type) {
			return warnedTypes.size() < 16 && warnedTypes.add(type);
		}
	}

	record Inbound(String type, String activity, AdvertisementRequest request, AdvertisementUpdate patch, String id, String key,
		String code, String host, Long accountHash, Boolean visible, String newKey, String name, String target,
		/** Sent with {@code revokeDevice}/{@code renameDevice}: the id (token digest) of the target device. */
		String deviceId,
		/** Sent with {@code renameDevice}: the new label. */
		String label,
		/**
		 * Sent with {@code discordRecoveryPoll}: the secret handed back by {@code startDiscordRecovery}.
		 * Held by the client rather than the session so a reconnect mid-flow does not lose an authorisation
		 * the user has already given at Discord.
		 */
		String ticket,
		/** Sent with {@code transferHost}: the incoming host's account type, which re-stamps the ad's badge. */
		String hostAccountType,
		/** Sent with {@code subscribe}: this client can read gzipped binary frames. Absent means it cannot. */
		Boolean compress,
		/**
		 * Sent with {@code subscribe} by a client that still holds a board: the revision it last applied.
		 * Answered with the changes since, or with a whole board when it has been away too long.
		 */
		Long since) {

		/**
		 * Fold "no account" to null however the client spells it.
		 *
		 * <p>The plugin sends {@code -1} when nobody is logged in, because that is what
		 * {@code Client.getAccountHash()} returns; this service documents {@code 0} as unknown and the checks
		 * here are all written as {@code != 0}. Left alone, {@code -1} passes every one of them, so every
		 * logged-out client shares a single "known" identity -- one that can be looked up in the Discord
		 * tables, indexed for invite routing, and matched against bans.
		 *
		 * <p>Normalising at decode rather than at each use means a check added later cannot miss the case.
		 */
		Inbound {
			if (accountHash != null && (accountHash == -1L || accountHash == 0L)) {
				accountHash = null;
			}
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Outbound(String type, long version, List<Advertisement> ads, Advertisement ad, String id, String detail,
		Integer online, String url, String username, Long accountHash, Boolean badgesVisible, String from,
		Boolean delivered,
		/** Snapshot only: the revision this board is current to, which the client offers back on resume. */
		Long seq) {
		/**
		 * {@code ads} and {@code ad} under the names plugin 1.0.50 reads them by. That client deserialises
		 * with Gson, which drops a field it has no name for without complaining, so an unaliased frame costs
		 * it an empty search list and a lookup that never resolves.
		 *
		 * <p>Both names ride every frame that carries either, which is cheap: {@code @JsonInclude} drops them
		 * when null, and the only frames where they are not are the ones a client is waiting on anyway.
		 * Goes when {@link net.osparty.api.web.CapabilitiesController} does.
		 */
		@JsonGetter("parties")
		public List<Advertisement> parties() {
			return ads;
		}

		@JsonGetter("party")
		public Advertisement party() {
			return ad;
		}

		static Outbound snapshot(long version, List<Advertisement> ads, Long seq) {
			return new Outbound("snapshot", version, ads, null, null, null, null, null, null, null, null, null, null, seq);
		}

		static Outbound hosted(long version, Advertisement ad) {
			return new Outbound("hosted", version, null, ad, null, null, null, null, null, null, null, null, null, null);
		}

		static Outbound gone(long version, String id) {
			return new Outbound("gone", version, null, null, id, null, null, null, null, null, null, null, null, null);
		}

		static Outbound error(long version, String id, String detail) {
			return new Outbound("error", version, null, null, id, detail, null, null, null, null, null, null, null, null);
		}

		static Outbound byCode(long version, String code, Advertisement ad) {
			return new Outbound("byCode", version, null, ad, code, null, null, null, null, null, null, null, null, null);
		}

		static Outbound byHost(long version, String host, Advertisement ad) {
			return new Outbound("byHost", version, null, ad, host, null, null, null, null, null, null, null, null, null);
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

		static Outbound invited(long version, Advertisement ad, String from) {
			return new Outbound("invited", version, null, ad, null, null, null, null, null, null, null, from, null, null);
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
	record Batch(String type, long version, List<Advertisement> created, List<AdvertisementDelta> updated,
		List<String> removed, Long seq) {
	}
}

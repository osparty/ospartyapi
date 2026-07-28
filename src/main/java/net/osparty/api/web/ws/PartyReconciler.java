package net.osparty.api.web.ws;

import net.osparty.api.repository.PartyRepository;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyDelta;
import net.osparty.api.service.BanService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Diffs the shared party store against this node's last view and pushes the delta to subscribers.
 *
 * <h2>Two axes, not one</h2>
 * A party has two independent properties that each change over time: whether the record still
 * exists, and whether it is publicly visible. Collapsing them -- treating "absent from the public
 * list" as "gone" -- is what makes a shadowban destroy the banned host's Discord voice channel, and
 * it is a bug that predates bans: {@code RedisPartyRepository.list()} already filters out private
 * parties, so toggling an ad to private used to delete a live voice channel and eject everyone from
 * it. Tracking visibility separately fixes both at once.
 *
 * <p>Concretely: voice-channel garbage collection follows <em>existence</em> only, while board
 * events follow <em>visibility</em>. A party that goes hidden emits a {@code removed} flagged as
 * such, so the broadcaster can withhold that one event from the host it belongs to; a party that
 * becomes visible again emits a plain {@code created}.
 */
@Component
@ConditionalOnProperty(name = "app.ws.enabled", havingValue = "true", matchIfMissing = true)
public class PartyReconciler {
	private final PartyRepository store;
	private final PartyBroadcaster broadcaster;
	private final net.osparty.api.service.VoiceChannelService voice;
	private final net.osparty.api.service.DiscordBadgeService badges;
	private final BanService bans;

	/**
	 * Volatile because scheduled tasks no longer share one thread. Under virtual threads each
	 * execution gets a fresh one, so a plain field would depend on the executor's own handoff for
	 * visibility of the previous tick's snapshot. Successive runs of a fixed-delay task never
	 * overlap, so the whole map is simply published by the reference write at the end of each pass.
	 */
	private volatile Map<String, Known> lastKnown = new HashMap<>();

	/** Ids announced since the last flush. A set, so a busy ad costs one read however often it changes. */
	private final Map<String, Long> dirty = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Advertisements that went away, and the revision they went away at. Kept so a client that reconnects
	 * can be told what disappeared while it was gone — an ad that is simply absent cannot say so itself.
	 * Pruned by age; {@link #prunedThroughSeq} remembers how far the pruning got, and a client asking to
	 * resume from before that is sent the whole board instead.
	 */
	private final Map<String, Tombstone> tombstones = new java.util.LinkedHashMap<>();
	private long prunedThroughSeq;
	/** Whether {@link #startHistory()} has run; see it for why a fresh node refuses to resume.*/
	private boolean historyStarted;

	/** How long a removal is remembered. Generous next to a reconnect, cheap at an id and two longs. */
	private static final long TOMBSTONE_RETENTION_MS = 10 * 60 * 1000L;

	public PartyReconciler(PartyRepository store, PartyBroadcaster broadcaster,
		net.osparty.api.service.VoiceChannelService voice,
		net.osparty.api.service.DiscordBadgeService badges,
		BanService bans, PartyChangeBus changes) {
		this.store = store;
		this.broadcaster = broadcaster;
		this.voice = voice;
		this.badges = badges;
		// One ad changed somewhere in the cluster: handle that ad rather than waiting for the next sweep
		// over all of them. The sweep stays, for TTL expiries and for anything a lost message dropped.
		changes.setListener(this::onChange);
		// A ban changes what belongs on the board without changing any advertisement, so the board has to
		// be recomputed when the active set moves. No ad is re-read: only visibility can have changed.
		bans.setOnChange(this::onBansChanged);
		this.bans = bans;
	}

	@Scheduled(fixedDelayString = "${app.ws.reconcile-interval-ms:5000}")
	public synchronized void reconcile() {
		startHistory();
		List<Party> current = badges.enrichParties(store.list(null));
		Map<String, Known> currentById = new HashMap<>();
		for (Party party : current) {
			currentById.put(party.getId(), new Known(Party.copyOf(party), !bans.isHidden(party)));
		}

		List<Party> created = new ArrayList<>();
		List<PartyDelta> updated = new ArrayList<>();
		List<PartyBroadcaster.RemovedRef> removed = new ArrayList<>();

		// Records that genuinely disappeared: disbanded, or the host stopped heartbeating and the
		// ad TTL'd out. This is the only branch allowed to tear down a Discord voice channel.
		for (Map.Entry<String, Known> entry : lastKnown.entrySet()) {
			if (currentById.containsKey(entry.getKey())) {
				continue;
			}
			Known previous = entry.getValue();
			// A TTL expiry is the change nobody announces, so the sweep is where its tombstone is minted.
			if (!tombstones.containsKey(entry.getKey())) {
				entomb(entry.getKey(), 0, previous.party().getActivity());
			}
			if (previous.visible()) {
				removed.add(new PartyBroadcaster.RemovedRef(
					entry.getKey(), previous.party().getActivity(), false));
			}
			String channelId = previous.party().getDiscordChannelId();
			// Absent from the list is not the same as gone: list() hides private ads, so an ad that merely
			// went private looks identical here to one that disbanded. Only the store can tell them apart,
			// and getting it wrong destroys a voice channel with people in it — so ask, for the handful of
			// ads that vanish in a tick.
			if (channelId != null && store.findById(entry.getKey()).isEmpty()) {
				voice.delete(channelId);
			}
		}

		for (Party party : current) {
			Known previous = lastKnown.get(party.getId());
			boolean visible = currentById.get(party.getId()).visible();
			if (previous == null) {
				if (visible) {
					created.add(party);
				}
				continue;
			}
			if (!previous.visible() && visible) {
				// Unbanned, or flipped back to public: everyone else learns about it as a new ad.
				created.add(party);
			}
			else if (previous.visible() && !visible) {
				// Newly hidden. The record still exists and the host keeps using it, so no voice
				// teardown; the `hidden` flag tells the broadcaster to spare the host this event.
				removed.add(new PartyBroadcaster.RemovedRef(party.getId(), party.getActivity(), true));
			}
			else if (visible) {
				PartyDelta delta = PartyDelta.diff(previous.party(), party);
				if (delta != null) {
					updated.add(delta);
				}
			}
			// hidden -> hidden: nobody it is broadcast to can see it, so say nothing.
		}
		broadcaster.broadcastBatch(created, updated, removed);

		lastKnown = currentById;
		publishBoard();
	}

	/**
	 * An advertisement changed somewhere in the cluster. Note it and return: the work happens in
	 * {@link #flushChanges()}, on the bus listener thread's behalf rather than on it.
	 */
	void onChange(String id, Long seq) {
		if (id != null) {
			dirty.merge(id, seq == null ? 0L : seq, Math::max);
		}
	}

	/**
	 * Apply everything announced since the last flush, as one batch.
	 *
	 * <p>Collecting is the point. Broadcasting each change the instant it lands turns one board event into
	 * one send per subscriber, and sends are what this service's CPU tracks — a measured run produced one
	 * frame per delta where the five-second sweep produced one per twenty-three. The window gets that
	 * batching back without going back to re-reading every ad to find the few that moved.
	 *
	 * <p>Defaulted to the interval it replaces, so the send count and the board's latency both stay where
	 * they were and the only thing removed is the scanning. Lowering it buys a fresher board and is paid
	 * for in sends: at one second the same run produced four times the frames for the same information.
	 *
	 * <p>Shares {@code lastKnown} with the sweep, so both are serialised on this object.
	 */
	@Scheduled(fixedDelayString = "${app.ws.change-flush-ms:5000}")
	public synchronized void flushChanges() {
		if (dirty.isEmpty()) {
			return;
		}
		Map<String, Long> ids = new HashMap<>(dirty);
		ids.keySet().forEach(dirty::remove);

		List<Party> created = new ArrayList<>();
		List<PartyDelta> updated = new ArrayList<>();
		List<PartyBroadcaster.RemovedRef> removed = new ArrayList<>();
		for (Map.Entry<String, Long> entry : ids.entrySet()) {
			applyChange(entry.getKey(), entry.getValue(), created, updated, removed);
		}
		if (!created.isEmpty() || !updated.isEmpty() || !removed.isEmpty()) {
			broadcaster.broadcastBatch(created, updated, removed);
		}
		publishBoard();
	}

	/**
	 * One announced advertisement, folded into the batch being built. Call under this object's lock.
	 *
	 * <p>The same diff {@link #reconcile()} performs, narrowed to a single id: same visibility rules, same
	 * separation of existence from visibility, same voice-channel teardown on a genuine disappearance.
	 *
	 * <p>An id nobody here has seen and that no longer exists is not an error — it is a delete announced by
	 * a node whose creation we never happened to observe, and there is nothing to say about it.
	 */
	private void applyChange(String id, long seq, List<Party> created, List<PartyDelta> updated,
		List<PartyBroadcaster.RemovedRef> removed) {
		Known previous = lastKnown.get(id);
		Party party = store.findById(id).map(p -> badges.enrichParties(List.of(p)).get(0)).orElse(null);
		if (party == null) {
			if (previous == null) {
				return;
			}
			lastKnown.remove(id);
			entomb(id, seq, previous.party().getActivity());
			// fall through to the board events below
			if (previous.visible()) {
				removed.add(new PartyBroadcaster.RemovedRef(id, previous.party().getActivity(), false));
			}
			String channelId = previous.party().getDiscordChannelId();
			if (channelId != null) {
				voice.delete(channelId);
			}
			return;
		}

		// Mirrors what the sweep's list() would include: a private ad exists but is not on the board, which
		// is the same shape as a shadowbanned one. Recording it as visible would make the next sweep — whose
		// list cannot see it — read it as having disappeared.
		boolean visible = !party.isPrivateParty() && !bans.isHidden(party);
		lastKnown.put(id, new Known(Party.copyOf(party), visible));
		if (previous == null || (!previous.visible() && visible)) {
			if (visible) {
				created.add(party);
			}
		}
		else if (previous.visible() && !visible) {
			// The record still exists and its host keeps using it, so no voice teardown; the flag is what
			// lets the broadcaster spare the host this one event.
			removed.add(new PartyBroadcaster.RemovedRef(id, party.getActivity(), true));
		}
		else if (visible) {
			PartyDelta delta = PartyDelta.diff(previous.party(), party);
			if (delta != null) {
				updated.add(delta);
			}
		}
	}

	/**
	 * The active ban set moved: recompute visibility across what this node already knows, emit whatever
	 * appeared or disappeared, and rebuild the board.
	 *
	 * <p>No advertisement is re-read — a ban changes none of them, only whether they belong on the board.
	 */
	synchronized void onBansChanged() {
		List<Party> created = new ArrayList<>();
		List<PartyBroadcaster.RemovedRef> removed = new ArrayList<>();
		for (Map.Entry<String, Known> entry : lastKnown.entrySet()) {
			Known previous = entry.getValue();
			Party party = previous.party();
			boolean visible = !party.isPrivateParty() && !bans.isHidden(party);
			if (visible == previous.visible()) {
				continue;
			}
			entry.setValue(new Known(party, visible));
			if (visible) {
				created.add(party);
			}
			else {
				// Flagged hidden: the record still exists and its host keeps using it, so the broadcaster
				// withholds this one event from them and no voice channel is touched.
				removed.add(new PartyBroadcaster.RemovedRef(entry.getKey(), party.getActivity(), true));
			}
		}
		if (!created.isEmpty() || !removed.isEmpty()) {
			broadcaster.broadcastBatch(created, List.of(), removed);
			publishBoard();
		}
	}

	/**
	 * Hand the current board to the broadcaster, so joiners are served from it instead of re-reading every
	 * ad from Redis, re-enriching it and re-serialising it per connect. Called after the events it belongs
	 * with, so a joiner's snapshot and the delta stream describe the same instant.
	 */
	private void publishBoard() {
		List<Party> visible = new ArrayList<>(lastKnown.size());
		List<Party> hidden = new ArrayList<>();
		for (Known known : lastKnown.values()) {
			(known.visible() ? visible : hidden).add(known.party());
		}
		prune();
		broadcaster.publishBoard(visible, hidden, Map.copyOf(tombstones), prunedThroughSeq);
	}

	/**
	 * Remember that an advertisement went away, at the revision it went away at.
	 *
	 * <p>A removal is the one change that leaves nothing behind to describe it, so a client that reconnects
	 * after missing one would otherwise keep showing an ad that no longer exists until its next full board.
	 * Call under this object's lock.
	 */
	private void entomb(String id, long seq, String activity) {
		if (seq <= 0) {
			// Nobody told us a revision — a sweep-discovered TTL expiry. Take the next one so the removal
			// still has a place in the order.
			seq = store.nextRevision();
		}
		tombstones.put(id, new Tombstone(seq, activity, System.currentTimeMillis()));
		prune();
	}

	/**
	 * Mark where this node's knowledge of removals begins.
	 *
	 * <p>A node that has just started has no tombstones, and cannot get them: an advertisement deleted
	 * while it was down left nothing behind anywhere. Resuming a client from before that point would leave
	 * it showing an ad that no longer exists, with nothing to ever correct it — so those clients are sent
	 * the board instead, which is what they got before any of this existed.
	 *
	 * <p>The cost is that a rolling deploy still re-sends the board once per client. What resume buys is
	 * every <em>other</em> reconnect: a network blip, a party redirect, an owner failover, or anything at
	 * all reaching a node that has been up a while.
	 */
	private void startHistory() {
		if (!historyStarted) {
			historyStarted = true;
			prunedThroughSeq = Math.max(prunedThroughSeq, store.nextRevision());
		}
	}

	/** Drop removals older than the retention window, remembering how far that got. */
	private void prune() {
		long cutoff = System.currentTimeMillis() - TOMBSTONE_RETENTION_MS;
		java.util.Iterator<Map.Entry<String, Tombstone>> it = tombstones.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Tombstone> entry = it.next();
			if (entry.getValue().removedAtMs() > cutoff) {
				// Insertion-ordered, and removals only ever arrive later, so the rest are younger still.
				break;
			}
			prunedThroughSeq = Math.max(prunedThroughSeq, entry.getValue().seq());
			it.remove();
		}
	}

	/** A party as this node last saw it, plus whether it was publicly visible at the time. */
	private record Known(Party party, boolean visible) {
	}

	/** An advertisement that is gone, the revision it went at, and when we noticed. */
	record Tombstone(long seq, String activity, long removedAtMs) {
	}
}

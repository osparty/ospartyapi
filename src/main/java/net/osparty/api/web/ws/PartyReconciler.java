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

	private Map<String, Known> lastKnown = new HashMap<>();

	public PartyReconciler(PartyRepository store, PartyBroadcaster broadcaster,
		net.osparty.api.service.VoiceChannelService voice,
		net.osparty.api.service.DiscordBadgeService badges,
		BanService bans) {
		this.store = store;
		this.broadcaster = broadcaster;
		this.voice = voice;
		this.badges = badges;
		this.bans = bans;
	}

	@Scheduled(fixedDelayString = "${app.ws.reconcile-interval-ms:5000}")
	public void reconcile() {
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
			if (previous.visible()) {
				removed.add(new PartyBroadcaster.RemovedRef(
					entry.getKey(), previous.party().getActivity(), false));
			}
			String channelId = previous.party().getDiscordChannelId();
			if (channelId != null) {
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
	}

	/** A party as this node last saw it, plus whether it was publicly visible at the time. */
	private record Known(Party party, boolean visible) {
	}
}

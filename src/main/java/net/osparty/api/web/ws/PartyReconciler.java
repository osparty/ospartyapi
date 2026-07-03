package net.osparty.api.web.ws;

import net.osparty.api.repository.PartyRepository;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyDelta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ws.enabled", havingValue = "true", matchIfMissing = true)
public class PartyReconciler {
	private final PartyRepository store;
	private final PartyBroadcaster broadcaster;
	private final net.osparty.api.service.VoiceChannelService voice;

	private Map<String, Party> lastKnown = new HashMap<>();

	public PartyReconciler(PartyRepository store, PartyBroadcaster broadcaster,
		net.osparty.api.service.VoiceChannelService voice) {
		this.store = store;
		this.broadcaster = broadcaster;
		this.voice = voice;
	}

	// TODO(scale): list() scans every ad each tick (KEYS on Redis); back it with a SCAN/index if one instance ever holds enough ads to bite.
	@Scheduled(fixedDelayString = "${app.ws.reconcile-interval-ms:5000}")
	public void reconcile() {
		List<Party> current = store.list(null);
		// Snapshot copies, never the live repository instances: the fake/in-memory repo mutates ads
		// in place, so holding a live reference across ticks would alias prev==cur and hide changes.
		Map<String, Party> currentById = new HashMap<>();
		for (Party party : current) {
			currentById.put(party.getId(), Party.copyOf(party));
		}

		// One batch per tick: full Party for new ads, a minimal field delta for changed ads, ids for
		// removed ads. The broadcaster fans this out as a single frame per subscriber.
		List<Party> created = new ArrayList<>();
		List<PartyDelta> updated = new ArrayList<>();
		List<PartyBroadcaster.RemovedRef> removed = new ArrayList<>();
		for (Map.Entry<String, Party> entry : lastKnown.entrySet()) {
			if (!currentById.containsKey(entry.getKey())) {
				removed.add(new PartyBroadcaster.RemovedRef(entry.getKey(), entry.getValue().getActivity()));
				// The party is gone (disbanded or TTL'd out): tear down its Discord channel if it had
				// one. No-op when Discord is disabled. A separate sweeper should catch any we miss here
				// (e.g. an event lost across a restart).
				String channelId = entry.getValue().getDiscordChannelId();
				if (channelId != null) {
					voice.delete(channelId);
				}
			}
		}
		for (Party party : current) {
			Party previous = lastKnown.get(party.getId());
			if (previous == null) {
				created.add(party);
			}
			else {
				PartyDelta delta = PartyDelta.diff(previous, party);
				if (delta != null) {
					updated.add(delta);
				}
			}
		}
		broadcaster.broadcastBatch(created, updated, removed);

		lastKnown = currentById;
	}
}

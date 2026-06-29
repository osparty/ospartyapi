package net.osparty.api.web.ws;

import net.osparty.api.repository.PartyRepository;
import net.osparty.api.model.Party;
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

	private Map<String, Seen> lastKnown = new HashMap<>();

	public PartyReconciler(PartyRepository store, PartyBroadcaster broadcaster) {
		this.store = store;
		this.broadcaster = broadcaster;
	}

	// TODO(scale): list() scans every ad each tick (KEYS on Redis); back it with a SCAN/index if one instance ever holds enough ads to bite.
	@Scheduled(fixedDelayString = "${app.ws.reconcile-interval-ms:5000}")
	public void reconcile() {
		List<Party> current = store.list(null);
		Map<String, Seen> currentById = new HashMap<>();
		for (Party party : current) {
			currentById.put(party.getId(), new Seen(party.getActivity(), party.toString()));
		}

		for (Map.Entry<String, Seen> entry : lastKnown.entrySet()) {
			if (!currentById.containsKey(entry.getKey())) {
				broadcaster.removed(entry.getKey(), entry.getValue().activity());
			}
		}
		for (Party party : current) {
			Seen previous = lastKnown.get(party.getId());
			if (previous == null) {
				broadcaster.created(party);
			}
			else if (!previous.fingerprint().equals(currentById.get(party.getId()).fingerprint())) {
				broadcaster.updated(party);
			}
		}

		lastKnown = currentById;
	}

	private record Seen(String activity, String fingerprint) {
	}
}

package net.osparty.api.web;

import net.osparty.api.PartyRepository;
import net.osparty.api.model.Party;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the WebSocket push: on a fixed interval it reads the current open-party
 * set and diffs it against the previous one, emitting {@code created}/
 * {@code updated}/{@code removed} deltas through {@link PartyBroadcaster}.
 *
 * <p>This is what decouples server load from user count: one diff per interval
 * per instance, regardless of how many clients are connected — instead of every
 * client polling the full list. Because it reads the shared store, multiple API
 * instances each reconcile independently and push to their own subscribers, with
 * no cross-instance bus needed (the shared Redis <i>is</i> the bus). Latency for
 * a change to reach clients is at most one interval.
 *
 * <p>Change detection diffs each ad's <b>value fingerprint</b> ({@link Party}'s
 * Lombok {@code toString()}, which renders every field), so any advertised field
 * changing (occupancy, world, layout, open roles, …) surfaces as an {@code updated}.
 * A fingerprint (not the {@link Party} reference) is required because the in-memory
 * store hands back live objects that later mutate in place — a reference compare
 * would never see the change. TTL-expired ads simply stop appearing in the list and
 * are emitted as {@code removed} — covering hosts that vanished without a clean
 * disband.
 */
@Component
@ConditionalOnProperty(name = "app.ws.enabled", havingValue = "true", matchIfMissing = true)
public class PartyReconciler
{
	private final PartyRepository store;
	private final PartyBroadcaster broadcaster;

	/** Last-seen public ads by id (value snapshot). Only touched on the scheduler thread. */
	private Map<String, Seen> lastKnown = new HashMap<>();

	public PartyReconciler(PartyRepository store, PartyBroadcaster broadcaster)
	{
		this.store = store;
		this.broadcaster = broadcaster;
	}

	// TODO(scale): list() scans every ad each tick (KEYS on Redis). Fine for one
	// small instance; back it with a Redis index/SCAN if a single instance ever
	// holds enough ads for the scan to bite.
	@Scheduled(fixedDelayString = "${app.ws.reconcile-interval-ms:5000}")
	public void reconcile()
	{
		List<Party> current = store.list(null);
		Map<String, Seen> currentById = new HashMap<>();
		for (Party party : current)
		{
			currentById.put(party.getId(), new Seen(party.getActivity(), party.toString()));
		}

		for (Map.Entry<String, Seen> entry : lastKnown.entrySet())
		{
			if (!currentById.containsKey(entry.getKey()))
			{
				broadcaster.removed(entry.getKey(), entry.getValue().activity());
			}
		}
		for (Party party : current)
		{
			Seen previous = lastKnown.get(party.getId());
			if (previous == null)
			{
				broadcaster.created(party);
			}
			else if (!previous.fingerprint().equals(currentById.get(party.getId()).fingerprint()))
			{
				broadcaster.updated(party);
			}
		}

		lastKnown = currentById;
	}

	/** A value snapshot of an ad: its activity (for removed events) + a full-field fingerprint. */
	private record Seen(String activity, String fingerprint)
	{
	}
}

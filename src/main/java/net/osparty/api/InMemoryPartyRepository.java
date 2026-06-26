package net.osparty.api;

import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * In-memory ad store (the default). Not persisted — ads are short-lived and
 * disposable. Each ad has a {@code lastSeen} timestamp bumped by the host's
 * heartbeat; stale ones are reaped by {@link #evictStale(long)} via
 * {@link StaleAdEvictor}. Selected unless {@code app.storage=redis}.
 */
@Service
@ConditionalOnProperty(name = "app.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryPartyRepository implements PartyRepository
{
	private final Map<String, Party> parties = new ConcurrentHashMap<>();
	private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
	private final AtomicLong idSequence = new AtomicLong(1000);

	@Override
	public List<Party> list(String activity)
	{
		return parties.values().stream()
			.filter(p -> !p.isPrivateParty())
			.filter(p -> activity == null || activity.isBlank() || activity.equals(p.getActivity()))
			.sorted(Comparator.comparingLong(Party::getCreatedAt).reversed())
			.collect(Collectors.toList());
	}

	@Override
	public Optional<Party> findByInviteCode(String code)
	{
		String normalized = PartyFactory.normalizeInviteCode(code);
		if (normalized == null)
		{
			return Optional.empty();
		}
		return parties.values().stream()
			.filter(p -> normalized.equals(p.getInviteCode()))
			.findFirst();
	}

	@Override
	public Optional<Party> findByHost(String host)
	{
		if (host == null)
		{
			return Optional.empty();
		}
		return parties.values().stream()
			.filter(p -> PartyFactory.sameHost(p.getHost(), host))
			.findFirst();
	}

	@Override
	public Party create(PartyRequest request)
	{
		long now = System.currentTimeMillis();
		Party party = PartyFactory.fromRequest(request, nextId(), uniqueInviteCode(), now);

		// A host can only have one ad at a time — drop any previous one so
		// re-advertising replaces it instead of piling up.
		parties.values().removeIf(p ->
		{
			if (PartyFactory.sameHost(p.getHost(), request.host()))
			{
				lastSeen.remove(p.getId());
				return true;
			}
			return false;
		});

		parties.put(party.getId(), party);
		lastSeen.put(party.getId(), now);
		return party;
	}

	@Override
	public Optional<Party> heartbeat(String id, Integer size)
	{
		Party party = parties.get(id);
		if (party == null)
		{
			return Optional.empty();
		}
		lastSeen.put(id, System.currentTimeMillis());
		// Report current occupancy (membership is peer-to-peer; the host tells us).
		if (size != null && size > 0)
		{
			party.setSize(size);
		}
		return Optional.of(party);
	}

	@Override
	public Optional<Party> delete(String id)
	{
		lastSeen.remove(id);
		return Optional.ofNullable(parties.remove(id));
	}

	@Override
	public int evictStale(long maxAgeMs)
	{
		long cutoff = System.currentTimeMillis() - maxAgeMs;
		int removed = 0;
		for (Map.Entry<String, Long> entry : lastSeen.entrySet())
		{
			if (entry.getValue() < cutoff)
			{
				lastSeen.remove(entry.getKey());
				if (parties.remove(entry.getKey()) != null)
				{
					removed++;
				}
			}
		}
		return removed;
	}

	private String nextId()
	{
		return String.valueOf(idSequence.getAndIncrement());
	}

	private String uniqueInviteCode()
	{
		String code;
		do
		{
			code = PartyFactory.newInviteCode();
		}
		while (findByInviteCode(code).isPresent());
		return code;
	}
}

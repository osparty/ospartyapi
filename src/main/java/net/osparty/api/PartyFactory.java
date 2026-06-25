package net.osparty.api;

import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers for building a {@link Party} from a request and matching hosts. */
final class PartyFactory
{
	private PartyFactory()
	{
	}

	static Party fromRequest(PartyRequest request, String id, long now)
	{
		Party party = new Party();
		party.setId(id);
		party.setActivity(request.activity());
		party.setHost(request.host());
		party.setDescription(request.description());
		party.setCapacity(request.capacity());
		party.setWorld(request.world());
		party.setMinKillCount(request.minKillCount());
		party.setMinHardModeKillCount(request.minHardModeKillCount());
		party.setPassphrase(request.passphrase());
		party.setCreatedAt(now);
		// Advisory only — the host occupies the first slot until the live room takes over.
		party.setSize(1);
		List<String> members = new ArrayList<>();
		if (request.host() != null)
		{
			members.add(request.host());
		}
		party.setMembers(members);
		return party;
	}

	/** Case/whitespace-insensitive host comparison (RSNs may carry nbsp). */
	static boolean sameHost(String a, String b)
	{
		return a != null && b != null && normalizeHost(a).equals(normalizeHost(b));
	}

	static String normalizeHost(String host)
	{
		return host.replace('\u00A0', ' ').trim().toLowerCase();
	}
}

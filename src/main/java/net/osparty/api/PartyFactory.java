package net.osparty.api;

import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers for building a {@link Party} from a request and matching hosts. */
final class PartyFactory
{
	// Unambiguous alphabet (no 0/O/1/I) for human-friendly invite codes.
	private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
	private static final int CODE_LENGTH = 6;
	private static final SecureRandom RANDOM = new SecureRandom();

	private PartyFactory()
	{
	}

	static Party fromRequest(PartyRequest request, String id, String inviteCode, long now)
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
		party.setPrivateParty(request.privateParty());
		party.setInviteCode(inviteCode);
		party.setLootRule(normalizeLootRule(request.lootRule()));
		party.setIronmanOnly(request.ironmanOnly());
		party.setHostAccountType(request.hostAccountType());
		party.setHardMode(request.hardMode());
		party.setInvocation(request.invocation());
		party.setRequiredRoles(request.requiredRoles());
		party.setHostRole(request.hostRole());
		party.setNeededRoles(initialNeededRoles(request.requiredRoles(), request.hostRole()));
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

	/**
	 * The roles still open right after creation: the full required composition with
	 * the host's own role removed once (the host fills it). Null/empty in, null out.
	 */
	private static List<String> initialNeededRoles(List<String> requiredRoles, String hostRole)
	{
		if (requiredRoles == null || requiredRoles.isEmpty())
		{
			return requiredRoles;
		}
		List<String> needed = new ArrayList<>(requiredRoles);
		if (hostRole != null)
		{
			needed.remove(hostRole);
		}
		return needed;
	}

	/**
	 * Parse a comma-separated list of role ids (as sent on the heartbeat) into a
	 * list. Null/blank in -> null out (treated as "no update" by callers).
	 */
	static List<String> parseRoles(String csv)
	{
		if (csv == null || csv.isBlank())
		{
			return null;
		}
		List<String> roles = new ArrayList<>();
		for (String part : csv.split(","))
		{
			String role = part.trim();
			if (!role.isEmpty())
			{
				roles.add(role);
			}
		}
		return roles;
	}

	static String newInviteCode()
	{
		StringBuilder sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++)
		{
			sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
		}
		return sb.toString();
	}

	static String normalizeInviteCode(String code)
	{
		return code == null ? null : code.trim().toUpperCase();
	}

	private static String normalizeLootRule(String lootRule)
	{
		if (lootRule == null || lootRule.isBlank())
		{
			return "UNSPECIFIED";
		}
		return lootRule.trim().toUpperCase();
	}

	/**
	 * Whether a stored host credential matches the one supplied on a request. An ad
	 * with no stored credential ({@code stored} null/blank) is open to anyone — older
	 * clients that pre-date the feature. Otherwise the supplied key must match exactly
	 * (compared in constant time so a mismatch can't be timed out character by character).
	 */
	static boolean hostKeyAuthorized(String stored, String supplied)
	{
		if (stored == null || stored.isBlank())
		{
			return true;
		}
		if (supplied == null)
		{
			return false;
		}
		return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
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

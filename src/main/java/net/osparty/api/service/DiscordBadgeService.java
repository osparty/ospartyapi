package net.osparty.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.osparty.api.model.Member;
import net.osparty.api.model.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Discord-role badges ({@code developer}, {@code content_creator}, {@code beta_tester}, {@code backer})
 * shown by clients next to party hosts. The osparty-discord bot is the source of truth: it watches guild
 * role changes over the gateway and pushes badge sets here (see {@code InternalBadgeController}), keyed by
 * Discord user id — for <em>any</em> role-holding guild member, linked or not, so a user who links later
 * needs no extra work.
 *
 * <p>Badges are stored in Redis beside the link keys and are never written into stored ads: the broadcast
 * path stamps them onto outbound {@link Party} copies each reconcile tick / snapshot
 * ({@link #enrichParties}). A role change therefore reaches subscribers within one reconcile interval as an
 * ordinary members delta, and client-supplied rosters (which never carry badges) can't fight the
 * enrichment or leak spoofed badges.
 */
@Service
public class DiscordBadgeService {
	/** The recognised badges, in the canonical (display-priority) order clients render them in. */
	public static final List<String> CANONICAL_BADGES =
		List.of("developer", "content_creator", "beta_tester", "backer");

	private static final Logger log = LoggerFactory.getLogger(DiscordBadgeService.class);
	private static final String BADGE_KEY = "discordlink:badges:";
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final DiscordLinkService links;

	public DiscordBadgeService(StringRedisTemplate redis, ObjectMapper mapper, DiscordLinkService links) {
		this.redis = redis;
		this.mapper = mapper;
		this.links = links;
	}

	/** Upsert one user's badge set. An empty/unrecognised set deletes the key (no badges). */
	public void setBadges(String discordId, List<String> badges) {
		List<String> canonical = sanitize(badges);
		if (canonical.isEmpty()) {
			redis.delete(BADGE_KEY + discordId);
			return;
		}
		try {
			redis.opsForValue().set(BADGE_KEY + discordId, mapper.writeValueAsString(canonical));
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to serialise badges", e);
		}
	}

	/**
	 * Full reconciliation, used by the bot's startup sweep: the given map becomes the complete badge
	 * state — keys for users absent from it are deleted (covers role removals missed while the bot was
	 * offline). KEYS matches the repository's existing pattern; the badge keyspace is small.
	 */
	public void replaceAll(Map<String, List<String>> badgesByDiscordId) {
		Set<String> existing = redis.keys(BADGE_KEY + "*");
		if (existing != null) {
			for (String key : existing) {
				String discordId = key.substring(BADGE_KEY.length());
				if (!badgesByDiscordId.containsKey(discordId)) {
					redis.delete(key);
				}
			}
		}
		badgesByDiscordId.forEach(this::setBadges);
	}

	/**
	 * Stamp badges onto outbound parties, returning enriched copies (input and stored ads stay
	 * untouched — {@code copyOf} + fresh {@link Member} instances, since the in-memory test repository
	 * hands out live references). Parties with no badged member are returned as-is. Any Redis failure
	 * degrades to "no badges" rather than breaking the broadcast path.
	 */
	public List<Party> enrichParties(List<Party> parties) {
		Set<Long> hashes = new HashSet<>();
		for (Party party : parties) {
			if (party.getMembers() == null) {
				continue;
			}
			for (Member member : party.getMembers()) {
				if (member.getAccountHash() != 0) {
					hashes.add(member.getAccountHash());
				}
			}
		}
		if (hashes.isEmpty()) {
			return parties;
		}
		Map<Long, List<String>> badgesByHash;
		try {
			badgesByHash = badgesForAccountHashes(hashes);
		}
		catch (Exception e) {
			log.debug("Badge lookup failed; broadcasting without badges: {}", e.toString());
			return parties;
		}
		if (badgesByHash.isEmpty()) {
			return parties;
		}
		List<Party> out = new ArrayList<>(parties.size());
		for (Party party : parties) {
			out.add(enrich(party, badgesByHash));
		}
		return out;
	}

	/** Resolve accountHash → linked Discord id → badges, two batched Redis round-trips. */
	private Map<Long, List<String>> badgesForAccountHashes(Collection<Long> accountHashes) {
		Map<Long, String> discordIds = links.discordIdsForAccountHashes(accountHashes);
		if (discordIds.isEmpty()) {
			return Map.of();
		}
		List<String> ids = new ArrayList<>(new HashSet<>(discordIds.values()));
		List<String> keys = new ArrayList<>(ids.size());
		for (String id : ids) {
			keys.add(BADGE_KEY + id);
		}
		List<String> values = redis.opsForValue().multiGet(keys);
		if (values == null) {
			return Map.of();
		}
		Map<String, List<String>> byDiscordId = new HashMap<>();
		for (int i = 0; i < values.size(); i++) {
			String json = values.get(i);
			if (json == null) {
				continue;
			}
			try {
				List<String> badges = sanitize(mapper.readValue(json, STRING_LIST));
				if (!badges.isEmpty()) {
					byDiscordId.put(ids.get(i), badges);
				}
			}
			catch (Exception ignored) {
				// unparseable stored badge set; treat as none
			}
		}
		Map<Long, List<String>> out = new HashMap<>();
		discordIds.forEach((hash, id) -> {
			List<String> badges = byDiscordId.get(id);
			if (badges != null) {
				out.put(hash, badges);
			}
		});
		return out;
	}

	private static Party enrich(Party party, Map<Long, List<String>> badgesByHash) {
		List<Member> members = party.getMembers();
		if (members == null || members.isEmpty()) {
			return party;
		}
		boolean any = false;
		for (Member member : members) {
			if (badgesByHash.containsKey(member.getAccountHash())) {
				any = true;
				break;
			}
		}
		if (!any) {
			return party;
		}
		List<Member> enriched = new ArrayList<>(members.size());
		for (Member member : members) {
			enriched.add(new Member(member.getName(), member.getAccountHash(),
				badgesByHash.get(member.getAccountHash())));
		}
		Party copy = Party.copyOf(party);
		copy.setMembers(enriched);
		return copy;
	}

	/**
	 * Keep only recognised badges, deduplicated, in canonical order — so equal badge sets always
	 * serialise identically and never produce spurious member deltas.
	 */
	private static List<String> sanitize(List<String> badges) {
		if (badges == null || badges.isEmpty()) {
			return List.of();
		}
		List<String> canonical = new ArrayList<>(CANONICAL_BADGES.size());
		for (String badge : CANONICAL_BADGES) {
			if (badges.contains(badge)) {
				canonical.add(badge);
			}
		}
		return canonical;
	}
}

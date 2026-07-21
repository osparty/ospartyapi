package net.osparty.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * A single Discord account may be linked to several OSRS accounts (multi-account users). Verifies the
 * badge/role state fans out to every linked account, and that unlinking one account leaves the others
 * intact. The suite runs Redis-free (the {@code test} profile swaps in in-memory beans), so this backs
 * {@link DiscordLinkService}/{@link DiscordBadgeService} with an in-memory fake of the handful of
 * {@code StringRedisTemplate} operations they use.
 */
class DiscordLinkMultiAccountTest {
	private static final long ACCOUNT_A = 900001L;
	private static final long ACCOUNT_B = 900002L;
	private static final String DISCORD_ID = "discord-9001";

	/** In-memory stand-ins for Redis string keys and set keys. */
	private final Map<String, String> values = new HashMap<>();
	private final Map<String, Set<String>> sets = new HashMap<>();

	private DiscordLinkService links;
	private DiscordBadgeService badges;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		SetOperations<String, String> setOps = mock(SetOperations.class);
		when(redis.opsForValue()).thenReturn(valueOps);
		when(redis.opsForSet()).thenReturn(setOps);

		doAnswer(inv -> {
			values.put(inv.getArgument(0), inv.getArgument(1));
			return null;
		}).when(valueOps).set(anyString(), anyString());
		when(valueOps.get(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0)));
		when(valueOps.multiGet(anyCollection())).thenAnswer(inv -> {
			Collection<String> keys = inv.getArgument(0);
			List<String> out = new ArrayList<>(keys.size());
			for (String key : keys) {
				out.add(values.get(key));
			}
			return out;
		});
		when(redis.delete(anyString())).thenAnswer(inv -> values.remove(inv.getArgument(0)) != null);

		when(setOps.add(anyString(), anyString())).thenAnswer(inv -> {
			String key = inv.getArgument(0);
			String value = inv.getArgument(1);
			return sets.computeIfAbsent(key, k -> new HashSet<>()).add(value) ? 1L : 0L;
		});
		when(setOps.remove(anyString(), anyString())).thenAnswer(inv -> {
			String key = inv.getArgument(0);
			Set<String> set = sets.get(key);
			if (set == null) {
				return 0L;
			}
			boolean removed = set.remove(inv.<String>getArgument(1));
			if (set.isEmpty()) {
				sets.remove(key);
			}
			return removed ? 1L : 0L;
		});
		when(setOps.members(anyString())).thenAnswer(inv -> {
			Set<String> set = sets.get(inv.getArgument(0));
			return set == null ? null : new HashSet<>(set);
		});

		ObjectMapper mapper = new ObjectMapper();
		links = new DiscordLinkService(redis, mapper, "", "");
		badges = new DiscordBadgeService(redis, mapper, links);
	}

	@Test
	void badgesSyncToEveryAccountLinkedToTheSameDiscord() {
		links.link(ACCOUNT_A, DISCORD_ID, "user#1");
		links.link(ACCOUNT_B, DISCORD_ID, "user#1");
		badges.setBadges(DISCORD_ID, List.of("developer", "backer"));

		assertThat(links.accountHashesForDiscordId(DISCORD_ID)).containsExactlyInAnyOrder(ACCOUNT_A, ACCOUNT_B);

		Party enriched = badges.enrichParties(List.of(partyWith(ACCOUNT_A, ACCOUNT_B))).get(0);
		assertThat(badgeOf(enriched, ACCOUNT_A)).containsExactly("developer", "backer");
		assertThat(badgeOf(enriched, ACCOUNT_B)).containsExactly("developer", "backer");
	}

	@Test
	void unlinkingOneAccountLeavesTheOtherLinkedAndBadged() {
		links.link(ACCOUNT_A, DISCORD_ID, "user#1");
		links.link(ACCOUNT_B, DISCORD_ID, "user#1");
		badges.setBadges(DISCORD_ID, List.of("developer"));

		links.unlink(ACCOUNT_A);

		assertThat(links.getByAccountHash(ACCOUNT_A)).isEmpty();
		assertThat(links.getByAccountHash(ACCOUNT_B)).isPresent();
		assertThat(links.accountHashesForDiscordId(DISCORD_ID)).containsExactly(ACCOUNT_B);

		Party enriched = badges.enrichParties(List.of(partyWith(ACCOUNT_B))).get(0);
		assertThat(badgeOf(enriched, ACCOUNT_B)).containsExactly("developer");
	}

	@Test
	void relinkingAnAccountToAnotherDiscordMovesItOutOfTheOldSet() {
		String otherDiscord = DISCORD_ID + "-other";
		links.link(ACCOUNT_A, DISCORD_ID, "user#1");
		links.link(ACCOUNT_A, otherDiscord, "user#2");

		assertThat(links.accountHashesForDiscordId(DISCORD_ID)).doesNotContain(ACCOUNT_A);
		assertThat(links.accountHashesForDiscordId(otherDiscord)).containsExactly(ACCOUNT_A);
	}

	private static Party partyWith(long... accountHashes) {
		Party party = new Party();
		party.setId("p-multiaccount");
		List<Member> members = new ArrayList<>();
		for (long hash : accountHashes) {
			members.add(new Member("Acct" + hash, hash));
		}
		party.setMembers(members);
		return party;
	}

	private static List<String> badgeOf(Party party, long accountHash) {
		return party.getMembers().stream()
			.filter(m -> m.getAccountHash() == accountHash)
			.findFirst()
			.orElseThrow()
			.getBadges();
	}
}

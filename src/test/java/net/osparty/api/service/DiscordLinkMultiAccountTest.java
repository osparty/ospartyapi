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
import java.util.List;
import java.util.Map;
import net.osparty.api.model.Member;
import net.osparty.api.model.Advertisement;
import net.osparty.api.repository.InMemoryDiscordLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * A single Discord account may be linked to several OSRS accounts (multi-account users). Verifies the
 * badge/role state fans out to every linked account, and that unlinking one account leaves the others
 * intact.
 *
 * <p>Links live in Postgres now, so the reverse lookup is backed by
 * {@link InMemoryDiscordLinkRepository}; Redis remains only as the read mirror the broadcast path
 * uses, faked here with the handful of string operations it actually calls.
 */
class DiscordLinkMultiAccountTest {
	private static final long ACCOUNT_A = 900001L;
	private static final long ACCOUNT_B = 900002L;
	private static final String DISCORD_ID = "discord-9001";

	/** In-memory stand-in for the Redis mirror. */
	private final Map<String, String> values = new HashMap<>();

	private DiscordLinkService links;
	private DiscordBadgeService badges;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(valueOps);

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

		ObjectMapper mapper = new ObjectMapper();
		InMemoryDiscordLinkRepository repository = new InMemoryDiscordLinkRepository();
		links = new DiscordLinkService(redis, mapper, repository, "", "");
		badges = new DiscordBadgeService(redis, mapper, links, repository);
	}

	@Test
	void badgesSyncToEveryAccountLinkedToTheSameDiscord() {
		links.link(ACCOUNT_A, DISCORD_ID, "user#1");
		links.link(ACCOUNT_B, DISCORD_ID, "user#1");
		badges.setBadges(DISCORD_ID, List.of("developer", "backer"));

		assertThat(links.accountHashesForDiscordId(DISCORD_ID)).containsExactlyInAnyOrder(ACCOUNT_A, ACCOUNT_B);

		Advertisement enriched = badges.enrichAds(List.of(partyWith(ACCOUNT_A, ACCOUNT_B))).get(0);
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

		Advertisement enriched = badges.enrichAds(List.of(partyWith(ACCOUNT_B))).get(0);
		assertThat(badgeOf(enriched, ACCOUNT_B)).containsExactly("developer");
	}

	@Test
	void relinkingAnAccountToAnotherDiscordMovesItOffTheOldOne() {
		String otherDiscord = DISCORD_ID + "-other";
		links.link(ACCOUNT_A, DISCORD_ID, "user#1");
		links.link(ACCOUNT_A, otherDiscord, "user#2");

		assertThat(links.accountHashesForDiscordId(DISCORD_ID)).doesNotContain(ACCOUNT_A);
		assertThat(links.accountHashesForDiscordId(otherDiscord)).containsExactly(ACCOUNT_A);
		assertThat(links.getByAccountHash(ACCOUNT_A))
			.get()
			.extracting(DiscordLinkService.Link::discordId)
			.isEqualTo(otherDiscord);
	}

	/** Badge visibility is a deliberate privacy choice, so it must survive on the durable side. */
	@Test
	void hiddenBadgesAreStrippedFromBroadcasts() {
		links.link(ACCOUNT_A, DISCORD_ID, "user#1");
		links.link(ACCOUNT_B, DISCORD_ID, "user#1");
		badges.setBadges(DISCORD_ID, List.of("developer"));
		badges.setBadgesHidden(ACCOUNT_A, true);

		assertThat(badges.isBadgesHidden(ACCOUNT_A)).isTrue();
		assertThat(badges.isBadgesHidden(ACCOUNT_B)).isFalse();

		Advertisement enriched = badges.enrichAds(List.of(partyWith(ACCOUNT_A, ACCOUNT_B))).get(0);
		assertThat(badgeOf(enriched, ACCOUNT_A)).isNull();
		assertThat(badgeOf(enriched, ACCOUNT_B)).containsExactly("developer");
	}

	private static Advertisement partyWith(long... accountHashes) {
		Advertisement party = new Advertisement();
		party.setId("p-multiaccount");
		List<Member> members = new ArrayList<>();
		for (long hash : accountHashes) {
			members.add(new Member("Acct" + hash, hash));
		}
		party.setMembers(members);
		return party;
	}

	private static List<String> badgeOf(Advertisement party, long accountHash) {
		return party.getMembers().stream()
			.filter(m -> m.getAccountHash() == accountHash)
			.findFirst()
			.orElseThrow()
			.getBadges();
	}
}

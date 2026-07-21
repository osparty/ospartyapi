package net.osparty.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.osparty.api.model.Member;
import net.osparty.api.model.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * A single Discord account may be linked to several OSRS accounts (multi-account users). Verifies the
 * badge/role state fans out to every linked account, and that unlinking one account leaves the others
 * intact. Uses the live Redis the rest of the suite already relies on; keys are namespaced by unique
 * test ids and purged in {@link #cleanup()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DiscordLinkMultiAccountTest {
	private static final long ACCOUNT_A = 900001L;
	private static final long ACCOUNT_B = 900002L;
	private static final String DISCORD_ID = "discordlink-multiaccount-test-9001";

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private DiscordLinkService links;

	@Autowired
	private DiscordBadgeService badges;

	@Autowired
	private ObjectMapper mapper;

	@AfterEach
	void cleanup() {
		links.unlink(ACCOUNT_A);
		links.unlink(ACCOUNT_B);
		redis.delete("discordlink:badges:" + DISCORD_ID);
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
		try {
			links.link(ACCOUNT_A, DISCORD_ID, "user#1");
			links.link(ACCOUNT_A, otherDiscord, "user#2");

			assertThat(links.accountHashesForDiscordId(DISCORD_ID)).doesNotContain(ACCOUNT_A);
			assertThat(links.accountHashesForDiscordId(otherDiscord)).containsExactly(ACCOUNT_A);
		}
		finally {
			redis.delete("discordlink:accounts:" + otherDiscord);
		}
	}

	private static Party partyWith(long... accountHashes) {
		Party party = new Party();
		party.setId("p-multiaccount");
		List<Member> members = new java.util.ArrayList<>();
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

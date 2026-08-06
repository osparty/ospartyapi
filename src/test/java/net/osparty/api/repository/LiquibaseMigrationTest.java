package net.osparty.api.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import net.osparty.api.model.AdBan;
import net.osparty.api.model.AdReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The only test that touches real SQL. It runs under the {@code pgtest} profile so it does not pick
 * up {@code application-test.yml}'s datasource exclusions, and it is deliberately the only place a
 * container is required — the rest of the suite runs against the in-memory repositories.
 *
 * <p>Everything asserted here is a property the application relies on but cannot express in Java:
 * that the changelog applies from empty, that it is idempotent, and above all that the partial
 * unique indexes really do permit a re-ban after revocation while rejecting a second concurrent
 * active ban. That index is what makes the ban endpoint idempotent under two moderators clicking
 * at once, so it needs a test against Postgres rather than against a fake.
 */
@SpringBootTest(classes = LiquibaseMigrationTest.SqlOnlyContext.class,
	webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pgtest")
@Testcontainers
@EnabledIf("dockerUsableOrCi")
class LiquibaseMigrationTest {
	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	/**
	 * Just enough context to exercise SQL: a datasource, the changelog, and the three JDBC
	 * repositories.
	 *
	 * <p>Booting the whole application here would drag in the Redis-backed beans as well — and
	 * {@code RedisInviteBus} opens its pub/sub listener eagerly at startup, so the context would
	 * fail to build on any machine without Redis running, for a test that never touches it. Naming
	 * the beans explicitly keeps the test honest about its one dependency, and starts in a fraction
	 * of the time.
	 */
	@Configuration(proxyBeanMethods = false)
	@ImportAutoConfiguration({
		DataSourceAutoConfiguration.class,
		DataSourceTransactionManagerAutoConfiguration.class,
		JdbcTemplateAutoConfiguration.class,
		JdbcClientAutoConfiguration.class,
		LiquibaseAutoConfiguration.class})
	@Import({JdbcBanRepository.class, JdbcAdReportRepository.class, JdbcDiscordLinkRepository.class})
	static class SqlOnlyContext {
	}

	/**
	 * Skips locally when Docker is not reachable so {@code ./gradlew test} stays runnable on a
	 * machine without it, but never skips in CI — a silently-skipping schema test is worse than no
	 * schema test, and CI is the run that actually gates a deploy.
	 */
	static boolean dockerUsableOrCi() {
		if (System.getenv("CI") != null) {
			return true;
		}
		try {
			return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
		}
		catch (Throwable e) {
			return false;
		}
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired
	private JdbcClient db;

	@Autowired
	private BanRepository bans;

	@Autowired
	private AdReportRepository reports;

	@Autowired
	private DiscordLinkRepository discordLinks;

	@BeforeEach
	void reset() {
		db.sql("TRUNCATE ad_ban, ad_report, discord_link, account_preference, data_migration "
			+ "RESTART IDENTITY CASCADE").update();
	}

	@Test
	void discordLinkUpsertsAndResolvesBothDirections() {
		discordLinks.link(1L, "discord-1", "user one");
		discordLinks.link(2L, "discord-1", "user one");

		// One Discord account, many OSRS accounts — the index that replaced the old Redis set.
		assertThat(discordLinks.accountHashesFor("discord-1")).containsExactlyInAnyOrder(1L, 2L);
		assertThat(discordLinks.findByAccountHash(1L))
			.get().extracting(DiscordLinkRepository.Link::discordName).isEqualTo("user one");

		// Re-linking moves the account rather than duplicating it.
		discordLinks.link(1L, "discord-2", "user two");
		assertThat(discordLinks.accountHashesFor("discord-1")).containsExactly(2L);
		assertThat(discordLinks.accountHashesFor("discord-2")).containsExactly(1L);
		assertThat(discordLinks.countLinks()).isEqualTo(2);

		discordLinks.unlink(2L);
		assertThat(discordLinks.findByAccountHash(2L)).isEmpty();
	}

	@Test
	void badgeVisibilityPersistsIndependentlyOfAnyLink() {
		// handleSetBadgeVisibility accepts any account hash, linked or not, which is why this is a
		// table of its own rather than a column on discord_link.
		discordLinks.setBadgesHidden(55L, true);
		assertThat(discordLinks.isBadgesHidden(55L)).isTrue();
		assertThat(discordLinks.findByAccountHash(55L)).isEmpty();
		assertThat(discordLinks.findBadgesHidden()).containsExactly(55L);

		discordLinks.setBadgesHidden(55L, false);
		assertThat(discordLinks.isBadgesHidden(55L)).isFalse();
		assertThat(discordLinks.findBadgesHidden()).isEmpty();
		// Never set at all reads as "not hidden", not as an error.
		assertThat(discordLinks.isBadgesHidden(999L)).isFalse();
	}

	@Test
	void importNeverOverwritesWhatPostgresAlreadyHas() {
		discordLinks.link(7L, "current", "current name");

		assertThat(discordLinks.importIfAbsent(7L, "stale", "stale name")).isFalse();
		assertThat(discordLinks.importIfAbsent(8L, "fresh", "fresh name")).isTrue();

		assertThat(discordLinks.findByAccountHash(7L))
			.get().extracting(DiscordLinkRepository.Link::discordId).isEqualTo("current");
		assertThat(discordLinks.findByAccountHash(8L)).isPresent();
	}

	@Test
	void dataMigrationMarkerIsRecordedOnceAndReadBack() {
		assertThat(discordLinks.migrationCompleted("some-migration")).isFalse();
		discordLinks.markMigrationCompleted("some-migration", "links=3");
		assertThat(discordLinks.migrationCompleted("some-migration")).isTrue();
		// Two replicas finishing the same idempotent work must not collide.
		discordLinks.markMigrationCompleted("some-migration", "links=3");
		assertThat(db.sql("SELECT count(*) FROM data_migration").query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void changelogAppliedEveryChangeSet() {
		List<String> ids = db.sql("SELECT id FROM databasechangelog ORDER BY orderexecuted")
			.query(String.class)
			.list();
		assertThat(ids).containsExactly(
			"001-1-create-ad-report", "001-2-index-ad-report",
			"002-1-create-ad-ban", "002-2-index-ad-ban", "002-3-index-ad-report-ban",
			"003-1-create-discord-link", "003-2-create-account-preference",
			"004-1-create-data-migration",
			"005-1-create-account-credential", "005-2-create-account-enrolment-log");
		// A second startup must be a no-op; Liquibase records checksums, so a changed file would
		// have failed the context refresh above rather than reaching this assertion.
		assertThat(db.sql("SELECT count(*) FROM databasechangeloglock").query(Integer.class).single())
			.isEqualTo(1);
	}

	@Test
	void banRoundTripsThroughActiveAndRevoked() {
		AdBan ban = bans.ban("spam guy", "Spam Guy", 4242L, "boosting adverts", "111", "mod", null);
		assertThat(ban.getId()).isPositive();
		assertThat(ban.isActive()).isTrue();
		assertThat(bans.findActive()).extracting(BanRepository.ActiveBan::hostName).containsExactly("spam guy");

		List<AdBan> revoked = bans.revoke("spam guy", null, "222", "other mod", "appealed");
		assertThat(revoked).hasSize(1);
		assertThat(bans.findActive()).isEmpty();
		assertThat(bans.findById(ban.getId())).get()
			.satisfies(row -> {
				assertThat(row.isActive()).isFalse();
				assertThat(row.getRevokedByDiscordName()).isEqualTo("other mod");
				assertThat(row.getRevokeReason()).isEqualTo("appealed");
			});
	}

	@Test
	void secondBanOfLiveSubjectReturnsTheExistingRow() {
		AdBan first = bans.ban("spam guy", "Spam Guy", 4242L, "first", "111", "mod", null);
		AdBan second = bans.ban("spam guy", "Spam Guy", 4242L, "second", "222", "other mod", null);
		assertThat(second.getId()).isEqualTo(first.getId());
		assertThat(second.getReason()).isEqualTo("first");
		assertThat(bans.findActive()).hasSize(1);
	}

	@Test
	void reBanAfterRevocationCreatesANewRowAndKeepsHistory() {
		AdBan first = bans.ban("spam guy", "Spam Guy", 4242L, "first", "111", "mod", null);
		bans.revoke("spam guy", 4242L, "111", "mod", "gave them a chance");

		AdBan second = bans.ban("spam guy", "Spam Guy", 4242L, "back at it", "111", "mod", null);
		assertThat(second.getId()).isNotEqualTo(first.getId());
		assertThat(bans.findActive()).hasSize(1);
		// Both rows survive: the ban/unban/ban pattern is the point of the soft delete.
		assertThat(db.sql("SELECT count(*) FROM ad_ban").query(Integer.class).single()).isEqualTo(2);
	}

	@Test
	void banMatchesEitherIdentifierIndependently() {
		bans.ban("spam guy", "Spam Guy", 4242L, "boosting", "111", "mod", null);
		// A rename keeps the hash ban; a new account keeps the name ban.
		assertThat(bans.ban("renamed guy", "Renamed Guy", 4242L, "x", "111", "mod", null).getHostName())
			.isEqualTo("spam guy");
		assertThat(bans.ban("spam guy", "Spam Guy", 9999L, "x", "111", "mod", null).getAccountHash())
			.isEqualTo(4242L);
		assertThat(bans.findActive()).hasSize(1);
	}

	/**
	 * The older-client case: an advertisement with no account hash at all. Worth its own test
	 * because a null parameter is what exposes an untyped placeholder — Postgres cannot infer a type
	 * for a bare {@code ?} in {@code ? IS NOT NULL}, so this path failed to prepare at all while
	 * every hash-bearing ban worked fine.
	 */
	@Test
	void banAndRevokeByNameAloneWithNoAccountHash() {
		AdBan ban = bans.ban("legacy host", "Legacy Host", null, "boosting", "111", "mod", null);
		assertThat(ban.getAccountHash()).isNull();
		assertThat(bans.findActive()).extracting(BanRepository.ActiveBan::hostName)
			.containsExactly("legacy host");

		// Re-banning the same name resolves to the existing row rather than a constraint violation.
		assertThat(bans.ban("legacy host", "Legacy Host", null, "again", "111", "mod", null).getId())
			.isEqualTo(ban.getId());

		assertThat(bans.revoke("legacy host", null, "222", "mod2", "appealed")).hasSize(1);
		assertThat(bans.findActive()).isEmpty();
	}

	@Test
	void banByAccountHashAloneIsAllowed() {
		AdBan ban = bans.ban("", null, 777L, "hash only", "111", "mod", null);
		assertThat(ban.getHostName()).isEmpty();
		assertThat(bans.findActive()).extracting(BanRepository.ActiveBan::accountHash).containsExactly(777L);
	}

	@Test
	void reportRoundTripsIncludingJsonbSnapshot() {
		AdReport report = new AdReport();
		report.setPartyId("p-1");
		report.setHostName("spam guy");
		report.setHostNameRaw("Spam Guy");
		report.setHostAccountHash(4242L);
		report.setActivity("cox");
		report.setDescription("cheap fire cape service");
		report.setWorld("301");
		report.setCapacity(5);
		report.setPartySize(1);
		report.setInviteCode("ABC123");
		report.setAdSnapshot("{\"id\":\"p-1\",\"host\":\"Spam Guy\"}");
		report.setReporterName("honest player");
		report.setReporterAccountHash(1L);

		long id = reports.insert(report);
		assertThat(reports.findById(id)).get().satisfies(row -> {
			assertThat(row.getStatus()).isEqualTo(AdReport.STATUS_PENDING);
			assertThat(row.isNotified()).isFalse();
			assertThat(row.getAdSnapshot()).contains("\"host\": \"Spam Guy\"");
		});

		reports.markNotified(id, "chan-1", "msg-1");
		AdBan ban = bans.ban("spam guy", "Spam Guy", 4242L, "reported", "111", "mod", id);
		assertThat(reports.markReviewed(id, AdReport.STATUS_BANNED, "111", "mod", ban.getId())).isTrue();

		assertThat(reports.findById(id)).get().satisfies(row -> {
			assertThat(row.isNotified()).isTrue();
			assertThat(row.getDiscordMessageId()).isEqualTo("msg-1");
			assertThat(row.getStatus()).isEqualTo(AdReport.STATUS_BANNED);
			assertThat(row.getResultingBanId()).isEqualTo(ban.getId());
		});
		assertThat(bans.findById(ban.getId())).get()
			.extracting(AdBan::getSourceReportId).isEqualTo(id);
	}

	@Test
	void purgeRemovesOnlyStalePendingReports() {
		long pendingOld = insertMinimal("old-pending");
		long pendingNew = insertMinimal("new-pending");
		long bannedOld = insertMinimal("old-banned");
		reports.markReviewed(bannedOld, AdReport.STATUS_BANNED, "111", "mod", null);
		db.sql("UPDATE ad_report SET created_at = now() - interval '120 days' WHERE id IN (?, ?)")
			.params(pendingOld, bannedOld)
			.update();

		assertThat(reports.purgePending(Duration.ofDays(90))).isEqualTo(1);
		assertThat(reports.findById(pendingOld)).isEmpty();
		assertThat(reports.findById(pendingNew)).isPresent();
		// A banned report outlives the purge: the Unban button resolves its subject through it.
		assertThat(reports.findById(bannedOld)).isPresent();
	}

	private long insertMinimal(String partyId) {
		AdReport report = new AdReport();
		report.setPartyId(partyId);
		report.setHostName("someone");
		report.setAdSnapshot("{}");
		return reports.insert(report);
	}
}

package net.osparty.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import net.osparty.api.repository.DiscordLinkRepository;
import net.osparty.api.repository.InMemoryDiscordLinkRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The one-shot move of Discord links out of Redis and into Postgres.
 *
 * <p>Runs against a real Redis because the importer's correctness is mostly about how it enumerates
 * the keyspace — it must use SCAN, not KEYS, on an instance that is simultaneously serving every
 * live party — and a mocked cursor would assert nothing about that. Skips when nothing is listening
 * on localhost:6379.
 */
@EnabledIf("redisReachable")
class DiscordLinkImporterTest {
	private static final String HOST = "localhost";
	private static final int PORT = 6379;

	private static LettuceConnectionFactory factory;
	private static StringRedisTemplate redis;

	private final ObjectMapper mapper = new ObjectMapper();
	private InMemoryDiscordLinkRepository repository;
	private DiscordLinkService links;
	private DiscordBadgeService badges;
	private DiscordLinkImporter importer;
	private String runId;

	static boolean redisReachable() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(HOST, PORT), 300);
			return true;
		}
		catch (IOException e) {
			return false;
		}
	}

	@BeforeAll
	static void connect() {
		factory = new LettuceConnectionFactory(HOST, PORT);
		factory.afterPropertiesSet();
		redis = new StringRedisTemplate(factory);
		redis.afterPropertiesSet();
	}

	@AfterAll
	static void disconnect() {
		factory.destroy();
	}

	@BeforeEach
	void setUp() {
		runId = UUID.randomUUID().toString();
		repository = new InMemoryDiscordLinkRepository();
		links = new DiscordLinkService(redis, mapper, repository, "", "");
		badges = new DiscordBadgeService(redis, mapper, links, repository);
		importer = new DiscordLinkImporter(redis, mapper, repository, links, badges);
	}

	@AfterEach
	void cleanUp() {
		deleteKeys("discordlink:hash:9*");
		deleteKeys("discordlink:badgeshidden:9*");
	}

	@Test
	void importsPreExistingRedisLinksAndHiddenPreferences() {
		long a = seedLink("discord-a", "user-a");
		long b = seedLink("discord-b", "user-b");
		seedHidden(a);

		DiscordLinkImporter.Result result = importer.importOnce();

		assertThat(result.ran()).isTrue();
		assertThat(result.links()).isGreaterThanOrEqualTo(2);
		assertThat(repository.findByAccountHash(a))
			.get().extracting(DiscordLinkRepository.Link::discordId).isEqualTo("discord-a");
		assertThat(repository.findByAccountHash(b))
			.get().extracting(DiscordLinkRepository.Link::discordName).isEqualTo("user-b");
		assertThat(repository.isBadgesHidden(a)).isTrue();
		assertThat(repository.isBadgesHidden(b)).isFalse();
	}

	@Test
	void isANoOpOnceCompleted() {
		seedLink("discord-a", "user-a");
		assertThat(importer.importOnce().ran()).isTrue();

		// A second start must not replay: the marker row is what makes this a one-shot migration.
		seedLink("discord-c", "user-c");
		DiscordLinkImporter.Result second = importer.importOnce();

		assertThat(second.ran()).isFalse();
		assertThat(second.links()).isZero();
	}

	/**
	 * Anything already in Postgres was written after the cutover, so it is newer than whatever the
	 * import is replaying out of Redis and must not be clobbered by it.
	 */
	@Test
	void postgresWinsAConflictWithStaleRedisData() {
		long accountHash = seedLink("stale-discord-id", "stale-name");
		repository.link(accountHash, "current-discord-id", "current-name", true);

		importer.importOnce();

		assertThat(repository.findByAccountHash(accountHash))
			.get().extracting(DiscordLinkRepository.Link::discordId).isEqualTo("current-discord-id");
	}

	@Test
	void skipsUnparseableRowsRatherThanAbandoningTheImport() {
		long good = seedLink("discord-good", "user-good");
		long broken = accountHash();
		redis.opsForValue().set("discordlink:hash:" + broken, "{not json");

		DiscordLinkImporter.Result result = importer.importOnce();

		assertThat(result.ran()).isTrue();
		assertThat(repository.findByAccountHash(good)).isPresent();
		assertThat(repository.findByAccountHash(broken)).isEmpty();
	}

	@Test
	void warmingRepublishesPostgresIntoTheRedisMirror() {
		long accountHash = accountHash();
		repository.link(accountHash, "discord-warm", "user-warm", true);
		repository.setBadgesHidden(accountHash, true);
		redis.delete("discordlink:hash:" + accountHash);

		importer.warmMirror();

		// Served from the mirror: this is the path the broadcast hot loop takes.
		assertThat(links.discordIdsForAccountHashes(List.of(accountHash)))
			.containsEntry(accountHash, "discord-warm");
		assertThat(redis.opsForValue().get("discordlink:badgeshidden:" + accountHash)).isEqualTo("1");
	}

	/**
	 * A flushed mirror must not read as "not linked": the read-through repopulates it from the
	 * durable side rather than silently dropping someone's Discord link.
	 */
	@Test
	void aMissingMirrorEntryIsRepairedFromPostgresOnRead() {
		long accountHash = accountHash();
		repository.link(accountHash, "discord-repair", "user-repair", true);
		redis.delete("discordlink:hash:" + accountHash);

		assertThat(links.getByAccountHash(accountHash))
			.get().extracting(DiscordLinkService.Link::discordId).isEqualTo("discord-repair");
		assertThat(redis.opsForValue().get("discordlink:hash:" + accountHash)).contains("discord-repair");
	}

	// --- helpers -------------------------------------------------------------------------------

	/** Account hashes start with 9 so the cleanup glob cannot touch a developer's real data. */
	private long accountHash() {
		return 900_000_000_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
	}

	private long seedLink(String discordId, String username) {
		long accountHash = accountHash();
		try {
			redis.opsForValue().set("discordlink:hash:" + accountHash,
				mapper.writeValueAsString(new DiscordLinkService.Link(discordId, username)));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return accountHash;
	}

	private void seedHidden(long accountHash) {
		redis.opsForValue().set("discordlink:badgeshidden:" + accountHash, "1");
	}

	private void deleteKeys(String pattern) {
		try (org.springframework.data.redis.core.Cursor<String> cursor = redis.scan(
			org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(500).build())) {
			while (cursor.hasNext()) {
				redis.delete(cursor.next());
			}
		}
	}
}

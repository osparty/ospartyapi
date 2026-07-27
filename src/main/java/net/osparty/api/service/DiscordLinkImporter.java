package net.osparty.api.service;

import java.util.ArrayList;
import java.util.List;
import net.osparty.api.repository.DiscordLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Moves the pre-existing Discord links and badge-visibility preferences out of Redis and into
 * Postgres once, then keeps the Redis mirror warm on every subsequent start.
 *
 * <h2>Two jobs, in order</h2>
 * <ol>
 *   <li><b>Import</b> — a one-shot replay of whatever was in Redis before the cutover. Runs at most
 *       once per cluster, tracked in {@code data_migration}. Postgres always wins a conflict:
 *       anything already stored was written after the cutover and is therefore newer than what the
 *       import is replaying.</li>
 *   <li><b>Warm</b> — repopulates the Redis mirror from Postgres on every start, so a flushed or
 *       lost Redis costs a warm-up rather than everybody's link.</li>
 * </ol>
 *
 * <p>The import is idempotent and its completion marker is written only after it succeeds. Three
 * replicas starting at once may therefore duplicate the work exactly once, which is cheap; the
 * alternative -- claiming first -- would let a replica that died mid-import leave the migration
 * permanently marked done and half-applied.
 *
 * <p>Enumerates with SCAN, never KEYS: KEYS blocks the single-threaded Redis server for a full
 * keyspace walk, and this is the same instance serving every live party.
 */
@Service
public class DiscordLinkImporter {
	private static final Logger log = LoggerFactory.getLogger(DiscordLinkImporter.class);

	private static final String MIGRATION_NAME = "discordlink-redis-to-postgres";
	private static final String HASH_KEY = "discordlink:hash:";
	private static final String HIDDEN_KEY = "discordlink:badgeshidden:";
	private static final int SCAN_BATCH = 500;

	private final StringRedisTemplate redis;
	private final com.fasterxml.jackson.databind.ObjectMapper mapper;
	private final DiscordLinkRepository repository;
	private final DiscordLinkService links;
	private final DiscordBadgeService badges;

	public DiscordLinkImporter(StringRedisTemplate redis,
		com.fasterxml.jackson.databind.ObjectMapper mapper,
		DiscordLinkRepository repository,
		DiscordLinkService links,
		DiscordBadgeService badges) {
		this.redis = redis;
		this.mapper = mapper;
		this.repository = repository;
		this.links = links;
		this.badges = badges;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		try {
			importOnce();
		}
		catch (Exception e) {
			// Never block startup on this. A failed import retries next boot because the marker is
			// only written on success, and the read-through in DiscordLinkService covers the gap.
			log.warn("Discord link import failed; will retry on next start: {}", e.toString());
		}
		try {
			warmMirror();
		}
		catch (Exception e) {
			log.warn("Discord link mirror warm-up failed: {}", e.toString());
		}
	}

	/** Replays Redis into Postgres. Safe to call repeatedly; a no-op once completed. */
	public Result importOnce() {
		if (repository.migrationCompleted(MIGRATION_NAME)) {
			log.debug("Discord link import already completed; skipping");
			return new Result(false, 0, 0);
		}
		int importedLinks = 0;
		for (String key : scan(HASH_KEY)) {
			Long accountHash = parseHash(key, HASH_KEY);
			if (accountHash == null) {
				continue;
			}
			String json = redis.opsForValue().get(key);
			if (json == null) {
				continue;
			}
			try {
				DiscordLinkService.Link link = mapper.readValue(json, DiscordLinkService.Link.class);
				if (link.discordId() != null
					&& repository.importIfAbsent(accountHash, link.discordId(), link.username())) {
					importedLinks++;
				}
			}
			catch (Exception e) {
				log.warn("Skipping unparseable link at {}: {}", key, e.toString());
			}
		}

		int importedHidden = 0;
		for (String key : scan(HIDDEN_KEY)) {
			Long accountHash = parseHash(key, HIDDEN_KEY);
			if (accountHash != null && repository.importHiddenIfAbsent(accountHash, true)) {
				importedHidden++;
			}
		}

		repository.markMigrationCompleted(MIGRATION_NAME,
			"links=" + importedLinks + " hidden=" + importedHidden);
		log.info("Imported {} Discord link(s) and {} badge-visibility preference(s) from Redis; "
			+ "Postgres now holds {} link(s)", importedLinks, importedHidden, repository.countLinks());
		return new Result(true, importedLinks, importedHidden);
	}

	/** Republishes Postgres into the Redis mirror the broadcast path reads. */
	public int warmMirror() {
		List<DiscordLinkRepository.Link> rows = repository.findAll();
		links.mirrorAll(rows);
		List<Long> hidden = repository.findBadgesHidden();
		badges.mirrorHidden(hidden);
		if (!rows.isEmpty() || !hidden.isEmpty()) {
			log.info("Warmed Discord link mirror: {} link(s), {} hidden-badge preference(s)",
				rows.size(), hidden.size());
		}
		return rows.size();
	}

	private List<String> scan(String prefix) {
		List<String> keys = new ArrayList<>();
		try (Cursor<String> cursor = redis.scan(
			ScanOptions.scanOptions().match(prefix + "*").count(SCAN_BATCH).build())) {
			while (cursor.hasNext()) {
				keys.add(cursor.next());
			}
		}
		return keys;
	}

	private static Long parseHash(String key, String prefix) {
		try {
			return Long.parseLong(key.substring(prefix.length()));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * @param ran false when the import had already completed on a previous start
	 */
	public record Result(boolean ran, int links, int hiddenPreferences) {
	}
}

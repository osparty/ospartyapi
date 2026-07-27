package net.osparty.api.repository;

import java.util.List;
import java.util.Optional;

/**
 * Durable store of OSRS-account &harr; Discord-account bindings and per-account preferences.
 *
 * <p>Postgres is authoritative for writes, but the broadcast path never reads through here: each
 * link is resolved for every party member on every reconcile tick, so reads stay on the Redis
 * mirror that {@code DiscordLinkService} maintains in front of this.
 */
public interface DiscordLinkRepository {
	/** Upserts a binding, replacing whatever Discord account was previously linked to this hash. */
	void link(long accountHash, String discordId, String discordName);

	void unlink(long accountHash);

	Optional<Link> findByAccountHash(long accountHash);

	/**
	 * All OSRS accounts bound to one Discord account. Replaces the old hand-maintained
	 * {@code discordlink:accounts:} Redis set with an index on the same table.
	 */
	List<Long> accountHashesFor(String discordId);

	/** Every binding, for warming the Redis mirror at startup. */
	List<Link> findAll();

	void setBadgesHidden(long accountHash, boolean hidden);

	boolean isBadgesHidden(long accountHash);

	/** Every account that has hidden its badges, for warming the Redis mirror at startup. */
	List<Long> findBadgesHidden();

	/**
	 * Inserts only if absent, for the one-shot Redis import. Postgres wins any conflict: anything
	 * already there was written after the cutover and is newer than what the import is replaying.
	 *
	 * @return true when a row was actually inserted
	 */
	boolean importIfAbsent(long accountHash, String discordId, String discordName);

	/** {@link #importIfAbsent} for the badge-visibility preference. */
	boolean importHiddenIfAbsent(long accountHash, boolean hidden);

	/** Whether the named one-shot data migration has already completed on this cluster. */
	boolean migrationCompleted(String name);

	/**
	 * Records a one-shot data migration as done.
	 *
	 * <p>Claimed <em>after</em> the work succeeds, not before. Claiming first would mean a replica
	 * that died mid-import leaves the migration marked done and permanently half-applied; claiming
	 * after means the worst case is that two replicas both do the same idempotent work once.
	 */
	void markMigrationCompleted(String name, String note);

	long countLinks();

	record Link(long accountHash, String discordId, String discordName) {
	}
}

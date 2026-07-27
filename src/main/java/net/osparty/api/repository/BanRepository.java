package net.osparty.api.repository;

import java.util.List;
import java.util.Optional;
import net.osparty.api.model.AdBan;

/**
 * Durable store of ad-board shadowbans.
 *
 * <p>Reads on the broadcast path never come through here — {@code BanService} keeps an in-memory
 * snapshot refreshed on an interval, so this interface only ever sees the snapshot reload and the
 * (rare) moderator write.
 */
public interface BanRepository {
	/** Every ban that has not been revoked, in the shape the broadcast filter matches on. */
	List<ActiveBan> findActive();

	Optional<AdBan> findById(long id);

	/**
	 * Bans a subject, or returns the ban already covering it. Idempotent so that two moderators
	 * clicking the same button produce one ban and two identical responses; the partial unique
	 * indexes on {@code ad_ban} are what make that safe under concurrency rather than a read-then-
	 * write check here.
	 *
	 * @param hostName normalized host name, or empty to ban by account hash alone
	 * @param accountHash null when the advertisement carried no account hash
	 */
	AdBan ban(String hostName, String hostNameRaw, Long accountHash, String reason,
		String moderatorDiscordId, String moderatorDiscordName, Long sourceReportId);

	/**
	 * Revokes every active ban matching either identifier. Returns the rows that were revoked,
	 * empty when the subject was not banned.
	 */
	List<AdBan> revoke(String hostName, Long accountHash, String moderatorDiscordId,
		String moderatorDiscordName, String reason);

	/** A ban reduced to the two identifiers the broadcast filter compares against. */
	record ActiveBan(long id, String hostName, Long accountHash) {
	}
}

package net.osparty.api.repository;

import java.time.Duration;
import java.util.Optional;
import net.osparty.api.model.AdReport;

/** Durable audit trail of player-submitted advertisement reports. */
public interface AdReportRepository {
	/** Persists the report and returns its generated id. */
	long insert(AdReport report);

	Optional<AdReport> findById(long id);

	/** Records that a review message was posted, so the row can be traced back to the message. */
	void markNotified(long id, String channelId, String messageId);

	/**
	 * Records a moderator decision. {@code banId} is null for a dismissal.
	 *
	 * @return false when the report no longer exists
	 */
	boolean markReviewed(long id, String status, String moderatorDiscordId,
		String moderatorDiscordName, Long banId);

	/**
	 * Deletes stale reports nobody acted on. Only {@code PENDING} rows are eligible: a banned
	 * report is what the Unban button resolves its subject through, so it must outlive the ban.
	 */
	int purgePending(Duration olderThan);
}

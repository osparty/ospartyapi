package net.osparty.api.service;

/**
 * Forwards a report to the Discord bot for human review.
 *
 * <p>Best-effort by contract, like {@link VoiceChannelService}: the report is already durably
 * recorded in Postgres before this is called, so a failure here costs a Discord message, not the
 * report itself.
 */
public interface AdReportService {
	/**
	 * Posts a review message.
	 *
	 * @return where the message landed, or empty when the bot is unconfigured or unreachable
	 */
	java.util.Optional<PostedReview> publish(ReviewRequest request);

	/**
	 * @param reportId the {@code ad_report} id the review buttons will carry
	 * @param hostAccountHash 0 when the reporting client never supplied one
	 * @param reporterName self-asserted; shown to moderators as a lead, not as a fact
	 * @param distinctReporters how many different clients have reported this advertisement
	 */
	record ReviewRequest(long reportId, String host, long hostAccountHash, String activity,
		String description, String world, Integer capacity, Integer partySize, String inviteCode,
		String reporterName, int distinctReporters) {
	}

	record PostedReview(String channelId, String messageId) {
	}
}

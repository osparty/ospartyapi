package net.osparty.api.service;

import java.util.Optional;

/**
 * No-op used when no Discord service URL is configured. Reports are still persisted; they simply do
 * not reach a moderator, which is the correct behaviour for a deployment with no bot.
 */
public class DisabledAdReportService implements AdReportService {
	@Override
	public Optional<PostedReview> publish(ReviewRequest request) {
		return Optional.empty();
	}
}

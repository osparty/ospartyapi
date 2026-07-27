package net.osparty.api.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.osparty.api.model.AdReport;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Test-profile stand-in for {@link JdbcAdReportRepository}. */
@Repository
@Profile("test")
public class InMemoryAdReportRepository implements AdReportRepository {
	private final Map<Long, AdReport> reports = new ConcurrentHashMap<>();
	private final AtomicLong idSequence = new AtomicLong(1);

	@Override
	public long insert(AdReport report) {
		long id = idSequence.getAndIncrement();
		report.setId(id);
		if (report.getCreatedAt() == null) {
			report.setCreatedAt(Instant.now());
		}
		reports.put(id, report);
		return id;
	}

	@Override
	public Optional<AdReport> findById(long id) {
		return Optional.ofNullable(reports.get(id));
	}

	@Override
	public void markNotified(long id, String channelId, String messageId) {
		AdReport report = reports.get(id);
		if (report != null) {
			report.setNotified(true);
			report.setDiscordChannelId(channelId);
			report.setDiscordMessageId(messageId);
		}
	}

	@Override
	public boolean markReviewed(long id, String status, String moderatorDiscordId,
		String moderatorDiscordName, Long banId) {
		AdReport report = reports.get(id);
		if (report == null) {
			return false;
		}
		report.setStatus(status);
		report.setReviewedAt(Instant.now());
		report.setReviewedByDiscordId(moderatorDiscordId);
		report.setReviewedByDiscordName(moderatorDiscordName);
		report.setResultingBanId(banId);
		return true;
	}

	@Override
	public int purgePending(Duration olderThan) {
		Instant cutoff = Instant.now().minus(olderThan);
		List<Long> stale = reports.values().stream()
			.filter(r -> AdReport.STATUS_PENDING.equals(r.getStatus()))
			.filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isBefore(cutoff))
			.map(AdReport::getId)
			.toList();
		stale.forEach(reports::remove);
		return stale.size();
	}

	/** Test hook: every report inserted so far, oldest first. */
	public List<AdReport> all() {
		return reports.values().stream()
			.sorted(java.util.Comparator.comparingLong(AdReport::getId))
			.toList();
	}

	/** Test hook: forget every report between cases. */
	public void clear() {
		reports.clear();
	}
}

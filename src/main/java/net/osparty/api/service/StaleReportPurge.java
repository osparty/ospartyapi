package net.osparty.api.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import net.osparty.api.repository.AdReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drops reports nobody ever acted on, so {@code ad_report} stays bounded by moderation activity
 * rather than by abuse volume. Mirrors {@link StalePartyPurge}.
 *
 * <p>Only {@code PENDING} rows are eligible. A report that produced a ban is what the Discord
 * Unban button resolves its subject through, so it has to outlive the ban itself; deleting one
 * would strand a live button with nothing to point at.
 */
@Component
public class StaleReportPurge {
	private static final Logger log = LoggerFactory.getLogger(StaleReportPurge.class);

	private final AdReportRepository reports;
	private final Duration retention;
	private final Counter reportsPurged;

	public StaleReportPurge(AdReportRepository reports,
		@Value("${app.reports.retention-days:90}") long retentionDays,
		MeterRegistry meterRegistry) {
		this.reports = reports;
		this.retention = Duration.ofDays(retentionDays);
		this.reportsPurged = Counter.builder("osparty.reports.purged")
			.description("Unreviewed advertisement reports deleted past their retention window")
			.register(meterRegistry);
	}

	@Scheduled(cron = "${app.reports.purge-cron:0 30 4 * * *}")
	public void purge() {
		try {
			int purged = reports.purgePending(retention);
			if (purged > 0) {
				log.info("Purged {} unreviewed ad report(s) older than {} days",
					purged, retention.toDays());
				reportsPurged.increment(purged);
			}
		}
		catch (Exception e) {
			log.warn("Ad report purge failed: {}", e.toString());
		}
	}
}

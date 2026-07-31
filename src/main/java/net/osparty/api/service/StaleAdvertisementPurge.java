package net.osparty.api.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.osparty.api.model.Advertisement;
import net.osparty.api.repository.AdvertisementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges stale advertisements: any ad older than the configured maximum age (120 minutes by
 * default) is deleted, regardless of host heartbeats. The board reconciler notices the
 * disappearance on its next pass and broadcasts the removal (and frees the voice channel).
 */
@Component
public class StaleAdvertisementPurge {
	private static final Logger log = LoggerFactory.getLogger(StaleAdvertisementPurge.class);

	private final AdvertisementRepository store;
	private final long maxAgeMs;
	private final Counter stalePartiesPurged;
	public StaleAdvertisementPurge(AdvertisementRepository store,
		@Value("${app.ads.stale-purge-age-ms:7200000}") long maxAgeMs,
		MeterRegistry meterRegistry) {
		this.store = store;
		this.maxAgeMs = maxAgeMs;
		this.stalePartiesPurged = Counter.builder("stale.parties.purged")
				.description("Number of stale parties purged")
				.register(meterRegistry);
	}

	@Scheduled(cron = "${app.ads.stale-purge-cron:0 */5 * * * *}")
	public void purge() {
		long now = System.currentTimeMillis();
		long cutoff = now - maxAgeMs;
		for (Advertisement ad : store.list(null)) {
			if (ad.getCreatedAt() > 0 && ad.getCreatedAt() < cutoff) {
				store.delete(ad.getId());
				log.info("Purged stale ad {} ({}, host {}) after {} minutes",
					ad.getId(), ad.getActivity(), ad.getHost(),
					(now - ad.getCreatedAt()) / 60_000);
				stalePartiesPurged.increment();
			}
		}
	}
}

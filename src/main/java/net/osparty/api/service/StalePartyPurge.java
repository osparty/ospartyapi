package net.osparty.api.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.osparty.api.model.Party;
import net.osparty.api.repository.PartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges stale parties: any party older than the configured maximum age (120 minutes by
 * default) is deleted, regardless of host heartbeats. The websocket reconciler notices the
 * disappearance on its next pass and broadcasts the removal (and frees the voice channel).
 */
@Component
public class StalePartyPurge {
	private static final Logger log = LoggerFactory.getLogger(StalePartyPurge.class);

	private final PartyRepository store;
	private final long maxAgeMs;
	private final Counter stalePartiesPurged;
	public StalePartyPurge(PartyRepository store,
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
		for (Party party : store.list(null)) {
			if (party.getCreatedAt() > 0 && party.getCreatedAt() < cutoff) {
				store.delete(party.getId());
				log.info("Purged stale party {} ({}, host {}) after {} minutes",
					party.getId(), party.getActivity(), party.getHost(),
					(now - party.getCreatedAt()) / 60_000);
				stalePartiesPurged.increment();
			}
		}
	}
}

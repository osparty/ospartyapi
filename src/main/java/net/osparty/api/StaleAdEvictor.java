package net.osparty.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically reaps ads whose host has stopped sending heartbeats — so a party
 * whose host crashed, logged out or closed the client doesn't linger in search
 * results forever.
 */
@Component
public class StaleAdEvictor
{
	private static final Logger log = LoggerFactory.getLogger(StaleAdEvictor.class);

	private final PartyRepository store;
	private final long ttlMs;

	public StaleAdEvictor(PartyRepository store, @Value("${app.ads.ttl-ms:90000}") long ttlMs)
	{
		this.store = store;
		this.ttlMs = ttlMs;
	}

	/** Runs on a fixed delay (default 30s); drops ads not heard from within the TTL. */
	@Scheduled(fixedDelayString = "${app.ads.evict-interval-ms:30000}")
	public void evictStale()
	{
		int removed = store.evictStale(ttlMs);
		if (removed > 0)
		{
			log.debug("Evicted {} stale ad(s)", removed);
		}
	}
}

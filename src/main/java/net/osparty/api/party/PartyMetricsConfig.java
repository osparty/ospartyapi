package net.osparty.api.party;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Live-party metrics (PARTY_V2_MIGRATION.md §9). Separate from the ad board's {@code MetricsConfig} because
 * the two answer different questions and are read on different dashboards.
 *
 * <p>Rooms live on the node that owns them, so {@code rooms.owned} / {@code members.connected} are the
 * inputs for the RAM capacity planning §16 R6 calls for; {@code redirects} and {@code failovers} show how
 * often node affinity sends a client elsewhere and how often ownership actually moves.
 *
 * <p>The metric names keep the {@code partyv2} prefix. They are a data contract with Prometheus, not source
 * identifiers: renaming them orphans every stored series, so the dashboards would show a gap at the deploy
 * and the history before it would only be reachable under the old name. Not worth it to delete four
 * characters.
 */
@Configuration
public class PartyMetricsConfig {

	@Bean
	MeterBinder partyMetrics(PartyManager manager) {
		return registry -> {
			Gauge.builder("osparty.partyv2.rooms.owned", manager, PartyManager::roomCount)
				.description("Live party rooms owned by this node")
				.register(registry);
			Gauge.builder("osparty.partyv2.members.connected", manager, PartyManager::connectedMembers)
				.description("Members connected to rooms owned by this node")
				.register(registry);
			FunctionCounter.builder("osparty.partyv2.redirects", manager, PartyManager::redirectCount)
				.description("Clients redirected to the node owning their party")
				.register(registry);
			FunctionCounter.builder("osparty.partyv2.failovers", manager, PartyManager::failoverCount)
				.description("Rooms drained after this node lost ownership")
				.register(registry);
			FunctionCounter.builder("osparty.partyv2.ownerpending", manager, PartyManager::ownerPendingCount)
				.description("Joins deferred because the room was mid-handover (host had not re-claimed yet)")
				.register(registry);
			FunctionCounter.builder("osparty.partyv2.reclaims", manager, PartyManager::reclaimCount)
				.description("Rooms taken over by this node after their owner expired")
				.register(registry);
			FunctionCounter.builder("osparty.partyv2.rebalances", manager, PartyManager::rebalanceCount)
				.description("New parties sent to a lighter node instead of being hosted here")
				.register(registry);
			FunctionCounter.builder("osparty.partyv2.pruned", manager, PartyManager::prunedCount)
				.description("Members swept after their socket closed without a close callback")
				.register(registry);
		};
	}
}

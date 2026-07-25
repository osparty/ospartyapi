package net.osparty.api.v2;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Party V2 metrics (PARTY_V2_MIGRATION.md §9). Separate from the V1 {@code MetricsConfig} so nothing here
 * loads unless {@code app.party-v2.enabled=true}.
 *
 * <p>Live state is server-side in V2, so {@code rooms.owned} / {@code members.connected} are the inputs for
 * the RAM capacity planning §16 R6 calls for; {@code redirects} and {@code failovers} show how often node
 * affinity sends a client elsewhere and how often ownership actually moves.
 */
@Configuration
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2MetricsConfig {

	@Bean
	MeterBinder partyV2Metrics(PartyV2Manager manager) {
		return registry -> {
			Gauge.builder("osparty.partyv2.rooms.owned", manager, PartyV2Manager::roomCount)
				.description("Live party rooms owned by this node")
				.register(registry);
			Gauge.builder("osparty.partyv2.members.connected", manager, PartyV2Manager::connectedMembers)
				.description("Members connected to rooms owned by this node")
				.register(registry);
			FunctionCounter.builder("osparty.partyv2.redirects", manager, PartyV2Manager::redirectCount)
				.description("Clients redirected to the node owning their party")
				.register(registry);
			FunctionCounter.builder("osparty.partyv2.failovers", manager, PartyV2Manager::failoverCount)
				.description("Rooms drained after this node lost ownership")
				.register(registry);
		};
	}
}

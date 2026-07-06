package net.osparty.api.web.config;

import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import net.osparty.api.web.ws.PartyBroadcaster;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production monitoring wiring. JVM/Tomcat metrics come for free from Actuator + Micrometer; this
 * class adds the three application-specific signals we care about:
 *
 * <ul>
 *   <li><b>Active socket connections</b> — a gauge over {@link PartyBroadcaster}'s live session map.
 *   <li><b>Redis query time/load</b> — Lettuce command-latency timers, wired via {@link ClientResources}.
 *   <li>a common {@code application} tag on every meter so dashboards can filter by service.
 * </ul>
 *
 * Everything here is inert unless something scrapes {@code /actuator/prometheus} (the prometheus
 * container in docker-compose), so it costs effectively nothing in local/test runs.
 */
@Configuration
public class MetricsConfig {

	/** Tag every metric with the service name so Grafana can filter (application="osrs-party-api"). */
	@Bean
	MeterRegistryCustomizer<MeterRegistry> commonTags(@Value("${spring.application.name}") String application) {
		return registry -> registry.config().commonTags("application", application);
	}

	/**
	 * Redis query time/load. Providing our own {@link ClientResources} makes Spring Data Redis's Lettuce
	 * client record per-command latency into Micrometer: it publishes {@code lettuce.command.completion}
	 * and {@code lettuce.command.firstresponse} timers, tagged by command (GET/SET/EXPIRE/...). From those
	 * you get both the latency your app sees and the command throughput. Spring Boot only creates its own
	 * default ClientResources when one isn't already defined, so this bean simply takes over.
	 */
	@Bean(destroyMethod = "shutdown")
	ClientResources lettuceClientResources(MeterRegistry registry) {
		MicrometerOptions options = MicrometerOptions.create();
		return DefaultClientResources.builder()
			.commandLatencyRecorder(new MicrometerCommandLatencyRecorder(registry, options))
			.build();
	}

	/**
	 * Active socket connections. Reads the live subscriber count off {@link PartyBroadcaster} on each
	 * scrape. Registered via an {@link ObjectProvider} because the broadcaster is conditional on
	 * {@code app.ws.enabled}; if WebSockets are disabled the gauge is simply not created.
	 */
	@Bean
	MeterBinder websocketConnectionsGauge(ObjectProvider<PartyBroadcaster> broadcaster) {
		return registry -> {
			PartyBroadcaster b = broadcaster.getIfAvailable();
			if (b != null) {
				Gauge.builder("osparty.ws.connections.active", b, PartyBroadcaster::activeConnections)
					.description("Currently connected WebSocket clients")
					.register(registry);
			}
		};
	}
}

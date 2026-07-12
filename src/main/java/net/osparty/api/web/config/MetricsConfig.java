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

@Configuration
public class MetricsConfig {

	@Bean
	MeterRegistryCustomizer<MeterRegistry> commonTags(@Value("${spring.application.name}") String application) {
		return registry -> registry.config().commonTags("application", application);
	}

	@Bean(destroyMethod = "shutdown")
	ClientResources lettuceClientResources(MeterRegistry registry) {
		MicrometerOptions options = MicrometerOptions.create();
		return DefaultClientResources.builder()
			.commandLatencyRecorder(new MicrometerCommandLatencyRecorder(registry, options))
			.build();
	}

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

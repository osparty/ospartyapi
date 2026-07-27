package net.osparty.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Mirrors {@link VoiceChannelServiceConfig}: same bot URL, same shared token, same on/off rule. */
@Configuration
public class AdReportServiceConfig {
	private static final Logger log = LoggerFactory.getLogger(AdReportServiceConfig.class);

	@Bean
	@ConditionalOnMissingBean(AdReportService.class)
	public AdReportService adReportService(
		@Value("${app.discord.service-url:}") String serviceUrl,
		@Value("${app.discord.internal-token:}") String internalToken) {
		if (serviceUrl == null || serviceUrl.isBlank()) {
			log.info("app.discord.service-url is unset — ad reports will be recorded but not "
				+ "forwarded to Discord (no-op)");
			return new DisabledAdReportService();
		}
		return new HttpAdReportService(serviceUrl, internalToken);
	}
}

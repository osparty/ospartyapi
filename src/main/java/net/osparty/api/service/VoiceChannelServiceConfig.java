package net.osparty.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VoiceChannelServiceConfig {
	private static final Logger log = LoggerFactory.getLogger(VoiceChannelServiceConfig.class);

	@Bean
	@ConditionalOnMissingBean(VoiceChannelService.class)
	public VoiceChannelService voiceChannelService(
		@Value("${app.discord.service-url:}") String serviceUrl,
		@Value("${app.discord.internal-token:}") String internalToken) {
		if (serviceUrl == null || serviceUrl.isBlank()) {
			log.info("app.discord.service-url is unset — voice-channel provisioning disabled (no-op)");
			return new DisabledVoiceChannelService();
		}
		return new HttpVoiceChannelService(serviceUrl, internalToken);
	}
}

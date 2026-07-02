package net.osparty.api.service;

import net.osparty.api.model.Party;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fallback {@link VoiceChannelService} used whenever the real {@link DiscordBotService} bean is
 * absent (i.e. {@code app.discord.enabled} is not {@code true}). Every call is a no-op returning
 * "no channel", so the create-voice-channel opcode simply reports failure to the host and the
 * reconciler's channel cleanup does nothing. Lets the app (and the whole test suite) run without a
 * Discord bot token.
 */
@Configuration
public class DisabledVoiceChannelService {
	@Bean
	@ConditionalOnMissingBean(VoiceChannelService.class)
	public VoiceChannelService noOpVoiceChannelService() {
		return new VoiceChannelService() {
			@Override
			public Optional<VoiceChannelInfo> createForParty(Party party) {
				return Optional.empty();
			}

			@Override
			public void delete(String channelId) {
				// no-op
			}
		};
	}
}

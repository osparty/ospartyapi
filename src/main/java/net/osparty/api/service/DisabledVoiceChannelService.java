package net.osparty.api.service;

import net.osparty.api.model.Party;
import java.util.Optional;

/**
 * No-op {@link VoiceChannelService} used whenever no Discord bot is wired (i.e. {@code app.discord.service-url}
 * is unset). Every call reports "no channel", so the create-voice-channel opcode simply reports failure to
 * the host and the reconciler's channel cleanup does nothing. Lets the app (and the whole test suite) run
 * without the osparty-discord service. Selected by {@link VoiceChannelServiceConfig}.
 */
public class DisabledVoiceChannelService implements VoiceChannelService {
	@Override
	public Optional<VoiceChannelInfo> createForParty(Party party, java.util.Collection<String> allowedDiscordIds) {
		return Optional.empty();
	}

	@Override
	public boolean grantAccess(String channelId, String discordId) {
		return false;
	}

	@Override
	public void revokeAccess(String channelId, String discordId) {
		// no-op
	}

	@Override
	public void delete(String channelId) {
		// no-op
	}

	@Override
	public void disconnectFromChannel(String channelId, String discordId) {
		// no-op
	}
}

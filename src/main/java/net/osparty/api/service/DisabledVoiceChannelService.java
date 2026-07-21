package net.osparty.api.service;

import net.osparty.api.model.Party;
import java.util.Optional;

public class DisabledVoiceChannelService implements VoiceChannelService {
	@Override
	public Optional<VoiceChannelInfo> createForParty(Party party, java.util.Collection<String> allowedDiscordIds) {
		return Optional.empty();
	}

	@Override
	public void rename(String channelId, Party party) {
	}

	@Override
	public boolean grantAccess(String channelId, String discordId) {
		return false;
	}

	@Override
	public void revokeAccess(String channelId, String discordId) {
	}

	@Override
	public void delete(String channelId) {
	}

	@Override
	public void disconnectFromChannel(String channelId, String discordId) {
	}
}

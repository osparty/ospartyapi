package net.osparty.api.service;

import net.osparty.api.model.Advertisement;
import java.util.Optional;

public class DisabledVoiceChannelService implements VoiceChannelService {
	@Override
	public Optional<VoiceChannelInfo> createForParty(Advertisement ad, java.util.Collection<String> allowedDiscordIds) {
		return Optional.empty();
	}

	@Override
	public void rename(String channelId, Advertisement ad) {
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

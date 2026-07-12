package net.osparty.api.service;

import net.osparty.api.model.Party;
import java.util.Optional;

public interface VoiceChannelService {
	Optional<VoiceChannelInfo> createForParty(Party party, java.util.Collection<String> allowedDiscordIds);

	boolean grantAccess(String channelId, String discordId);

	void revokeAccess(String channelId, String discordId);

	void delete(String channelId);

	void disconnectFromChannel(String channelId, String discordId);

	record VoiceChannelInfo(String channelId, String inviteUrl) {
	}
}

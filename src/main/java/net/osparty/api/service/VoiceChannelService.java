package net.osparty.api.service;

import net.osparty.api.model.Advertisement;
import java.util.Optional;

public interface VoiceChannelService {
	Optional<VoiceChannelInfo> createForParty(Advertisement ad, java.util.Collection<String> allowedDiscordIds);

	/** Rename an already-provisioned channel to reflect the party's current details (e.g. after a host transfer). */
	void rename(String channelId, Advertisement ad);

	boolean grantAccess(String channelId, String discordId);

	void revokeAccess(String channelId, String discordId);

	void delete(String channelId);

	void disconnectFromChannel(String channelId, String discordId);

	record VoiceChannelInfo(String channelId, String inviteUrl) {
	}
}

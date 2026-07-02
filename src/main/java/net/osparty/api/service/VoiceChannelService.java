package net.osparty.api.service;

import net.osparty.api.model.Party;
import java.util.Optional;

/**
 * Provisions and tears down a temporary voice channel for a party. Backed by
 * {@link DiscordBotService} when {@code app.discord.enabled=true}; otherwise a
 * {@link DisabledVoiceChannelService} no-op stands in so the wiring is always satisfied
 * (dev boxes and tests run without a Discord bot token).
 */
public interface VoiceChannelService {
	/**
	 * Create a voice channel for the party and mint an invite to it.
	 *
	 * @return the channel id and invite URL, or empty when disabled or the create failed.
	 */
	Optional<VoiceChannelInfo> createForParty(Party party);

	/** Delete a previously created channel by id. Best-effort; never throws. */
	void delete(String channelId);

	/** A provisioned channel: its Discord id (for later deletion) and the shareable invite URL. */
	record VoiceChannelInfo(String channelId, String inviteUrl) {
	}
}

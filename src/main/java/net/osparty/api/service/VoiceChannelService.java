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
	 * Create a voice channel for the party and mint an invite to it. {@code allowedDiscordIds} are the
	 * linked members granted per-user {@code VIEW_CHANNEL}/{@code CONNECT} on top of the {@code @everyone}
	 * deny, so the channel is truly locked to exactly those people.
	 *
	 * @return the channel id and invite URL, or empty when disabled or the create failed.
	 */
	Optional<VoiceChannelInfo> createForParty(Party party, java.util.Collection<String> allowedDiscordIds);

	/** Grant a linked member per-user access to an existing channel (for someone who joined/linked later). */
	void grantAccess(String channelId, String discordId);

	/**
	 * Revoke a member's per-user access to a channel by deleting their permission override, so with
	 * {@code @everyone} denied they can no longer see it at all. Used when kicking them from the party.
	 */
	void revokeAccess(String channelId, String discordId);

	/** Delete a previously created channel by id. Best-effort; never throws. */
	void delete(String channelId);

	/**
	 * Disconnect a Discord user from the given voice channel, but only if they are currently in it.
	 * Used to boot a kicked party member. Best-effort; never throws, and no-ops when disabled or the
	 * user isn't in that channel.
	 */
	void disconnectFromChannel(String channelId, String discordId);

	/**
	 * A provisioned channel: its Discord id (for later deletion), the shareable invite URL (gets a
	 * non-member into the guild), and the direct channel deep link the plugin opens for "Join voice"
	 * — that link takes an existing guild member straight to the voice channel, whereas an invite just
	 * lands them at the server.
	 */
	record VoiceChannelInfo(String channelId, String inviteUrl, String channelUrl) {
	}
}

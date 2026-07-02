package net.osparty.api.service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.entities.channel.attribute.IInviteContainer;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.osparty.api.model.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Level-1 Discord voice-channel provisioning via JDA. Active only when
 * {@code app.discord.enabled=true} (a valid bot token must be supplied); otherwise
 * {@link DisabledVoiceChannelService} stands in.
 *
 * <p>Each party gets one voice channel under a configured category, with {@code VIEW_CHANNEL}
 * denied to {@code @everyone} so the channel is unlisted and reachable only via the short-lived
 * invite we mint and hand back to the host. This gives "temporary, link-gated, not-public"
 * without any OSRS⇄Discord account linking (that is a later phase).
 *
 * <p>All Discord calls are synchronous ({@code complete()}): the create path runs on the WS
 * handler thread and the host is blocked on the reply anyway, and JDA still honours the gateway
 * rate limiter under the hood.
 */
@Service
@ConditionalOnProperty(prefix = "app.discord", name = "enabled", havingValue = "true")
public class DiscordBotService implements VoiceChannelService {
	private static final Logger log = LoggerFactory.getLogger(DiscordBotService.class);
	/** Discord hard limit on channel-name length. */
	private static final int MAX_CHANNEL_NAME = 100;

	private final JDA jda;
	private final long guildId;
	private final long categoryId;
	private final int inviteMaxAgeSeconds;
	private final int inviteMaxUses;

	public DiscordBotService(
		@Value("${app.discord.token}") String token,
		@Value("${app.discord.guild-id}") long guildId,
		@Value("${app.discord.category-id:0}") long categoryId,
		@Value("${app.discord.invite-max-age-seconds:86400}") int inviteMaxAgeSeconds,
		@Value("${app.discord.invite-max-uses:0}") int inviteMaxUses) throws InterruptedException {
		this.guildId = guildId;
		this.categoryId = categoryId;
		this.inviteMaxAgeSeconds = inviteMaxAgeSeconds;
		this.inviteMaxUses = inviteMaxUses;
		// createLight: no member/message cache and no privileged intents. We add GUILD_VOICE_STATES
		// (non-privileged) + the VOICE_STATE cache so we can tell whether a kicked member is currently
		// sitting in the party's channel before disconnecting them.
		this.jda = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES)
			.enableCache(CacheFlag.VOICE_STATE)
			.build().awaitReady();
		log.info("Discord bot connected as {} (guild={}, category={})",
			jda.getSelfUser().getName(), guildId, categoryId);
	}

	@Override
	public Optional<VoiceChannelInfo> createForParty(Party party) {
		Guild guild = jda.getGuildById(guildId);
		if (guild == null) {
			log.warn("Discord guild {} not found; cannot create channel for party {}", guildId, party.getId());
			return Optional.empty();
		}
		try {
			VoiceChannel channel = newChannelAction(guild, channelName(party))
				// Unlisted: @everyone cannot even see the channel; only the invite reveals it.
				.addPermissionOverride(guild.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL))
				.reason("OSParty voice channel for party " + party.getId())
				.complete();
			String url = ((IInviteContainer) channel).createInvite()
				.setMaxAge(inviteMaxAgeSeconds)
				.setMaxUses(inviteMaxUses)
				.setUnique(true)
				.complete()
				.getUrl();
			log.info("Created Discord voice channel {} for party {}", channel.getId(), party.getId());
			return Optional.of(new VoiceChannelInfo(channel.getId(), url));
		}
		catch (Exception e) {
			log.warn("Failed to create Discord voice channel for party {}: {}", party.getId(), e.toString());
			return Optional.empty();
		}
	}

	@Override
	public void delete(String channelId) {
		if (channelId == null || channelId.isBlank()) {
			return;
		}
		try {
			VoiceChannel channel = jda.getVoiceChannelById(channelId);
			if (channel != null) {
				channel.delete().reason("OSParty party ended").queue(
					ok -> log.info("Deleted Discord voice channel {}", channelId),
					err -> log.debug("Discord channel {} delete failed: {}", channelId, err.toString()));
			}
		}
		catch (Exception e) {
			log.debug("Discord channel {} delete threw: {}", channelId, e.toString());
		}
	}

	@Override
	public void disconnectFromChannel(String channelId, String discordId) {
		if (channelId == null || discordId == null) {
			return;
		}
		try {
			Guild guild = jda.getGuildById(guildId);
			if (guild == null) {
				return;
			}
			// A member in voice is cached (via VOICE_STATE) even under createLight; null => not in voice.
			Member member = guild.getMemberById(discordId);
			if (member == null) {
				return;
			}
			GuildVoiceState state = member.getVoiceState();
			if (state == null || state.getChannel() == null || !channelId.equals(state.getChannel().getId())) {
				return; // not in this party's channel — leave them alone
			}
			guild.kickVoiceMember(member).queue(
				ok -> log.info("Disconnected Discord user {} from channel {}", discordId, channelId),
				err -> log.debug("Voice disconnect failed for {}: {}", discordId, err.toString()));
		}
		catch (Exception e) {
			log.debug("disconnectFromChannel threw: {}", e.toString());
		}
	}

	private net.dv8tion.jda.api.requests.restaction.ChannelAction<VoiceChannel> newChannelAction(
		Guild guild, String name) {
		Category category = categoryId > 0 ? guild.getCategoryById(categoryId) : null;
		return category != null ? category.createVoiceChannel(name) : guild.createVoiceChannel(name);
	}

	/** e.g. {@code "Zezima | ABC123 | cox"}; falls back to the party id, trimmed to Discord's 100-char limit. */
	private static String channelName(Party party) {
		List<String> parts = new ArrayList<>();
		if (party.getHost() != null && !party.getHost().isBlank()) {
			parts.add(party.getHost().trim());
		}
		if (party.getInviteCode() != null && !party.getInviteCode().isBlank()) {
			parts.add(party.getInviteCode().trim());
		}
		if (party.getActivity() != null && !party.getActivity().isBlank()) {
			parts.add(party.getActivity().trim());
		}
		String name = parts.isEmpty() ? "party-" + party.getId() : String.join(" | ", parts);
		return name.length() > MAX_CHANNEL_NAME ? name.substring(0, MAX_CHANNEL_NAME) : name;
	}

	@PreDestroy
	public void shutdown() {
		jda.shutdown();
	}
}

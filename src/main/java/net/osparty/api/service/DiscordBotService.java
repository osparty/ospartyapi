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

	/**
	 * What a party member is granted on their channel: see it, connect, and actually talk. {@code VOICE_SPEAK}
	 * and {@code VOICE_USE_VAD} matter because guilds commonly disable those on {@code @everyone}, so without an
	 * explicit per-user allow a member could join but be muted (or forced to push-to-talk).
	 */
	private static final EnumSet<Permission> MEMBER_PERMS = EnumSet.of(
		Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK, Permission.VOICE_USE_VAD);

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
		// createLight: no message cache and no privileged intents. We add GUILD_VOICE_STATES
		// (non-privileged) + the VOICE_STATE cache and a VOICE member-cache policy so members currently
		// in a voice channel are cached — otherwise getMemberById() is null and we can't check which
		// channel a kicked member is in before disconnecting them.
		this.jda = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES)
			.enableCache(CacheFlag.VOICE_STATE)
			.setMemberCachePolicy(net.dv8tion.jda.api.utils.MemberCachePolicy.VOICE)
			.build().awaitReady();
		log.info("Discord bot connected as {} (guild={}, category={})",
			jda.getSelfUser().getName(), guildId, categoryId);
	}

	@Override
	public Optional<VoiceChannelInfo> createForParty(Party party, java.util.Collection<String> allowedDiscordIds) {
		Guild guild = jda.getGuildById(guildId);
		if (guild == null) {
			log.warn("Discord guild {} not found; cannot create channel for party {}", guildId, party.getId());
			return Optional.empty();
		}
		try {
			net.dv8tion.jda.api.requests.restaction.ChannelAction<VoiceChannel> action =
				newChannelAction(guild, channelName(party))
					// Unlisted: @everyone cannot even see the channel; only the invite reveals it.
					.addPermissionOverride(guild.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL));
			// Lock it to exactly the linked party members: grant each per-user view + connect.
			if (allowedDiscordIds != null) {
				for (String discordId : allowedDiscordIds) {
					try {
						action = action.addMemberPermissionOverride(Long.parseLong(discordId),
							MEMBER_PERMS, null);
					}
					catch (NumberFormatException ignored) {
						// skip a malformed id rather than fail the whole create
					}
				}
			}
			VoiceChannel channel = action
				.reason("OSParty voice channel for party " + party.getId())
				.complete();
			String inviteUrl = ((IInviteContainer) channel).createInvite()
				.setMaxAge(inviteMaxAgeSeconds)
				.setMaxUses(inviteMaxUses)
				.setUnique(true)
				.complete()
				.getUrl();
			log.info("Created Discord voice channel {} for party {}", channel.getId(), party.getId());
			return Optional.of(new VoiceChannelInfo(channel.getId(), inviteUrl));
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
	public boolean grantAccess(String channelId, String discordId) {
		if (channelId == null || discordId == null) {
			return false;
		}
		try {
			VoiceChannel channel = jda.getVoiceChannelById(channelId);
			if (channel == null) {
				return false;
			}
			// Run synchronously (complete()) so we only report success once the override has actually
			// landed on Discord's side — the caller opens the invite the moment we reply, and if the
			// grant were still in flight the member would find the channel invisible on their first try
			// and have to retry. The member may not be cached, so fetch them first.
			Member member = channel.getGuild().retrieveMemberById(Long.parseLong(discordId)).complete();
			channel.upsertPermissionOverride(member)
				.grant(MEMBER_PERMS)
				.reason("OSParty voice access")
				.complete();
			log.info("Granted Discord user {} access to channel {}", discordId, channelId);
			return true;
		}
		catch (Exception e) {
			log.warn("grantAccess failed for {} on {}: {}", discordId, channelId, e.toString());
			return false;
		}
	}

	@Override
	public void revokeAccess(String channelId, String discordId) {
		if (channelId == null || discordId == null) {
			return;
		}
		try {
			VoiceChannel channel = jda.getVoiceChannelById(channelId);
			if (channel == null) {
				return;
			}
			channel.getGuild().retrieveMemberById(Long.parseLong(discordId)).queue(member -> {
				net.dv8tion.jda.api.entities.PermissionOverride override = channel.getPermissionOverride(member);
				if (override != null) {
					override.delete().queue(
						ok -> log.info("Revoked Discord user {} access to channel {} (deleted override)",
							discordId, channelId),
						err -> log.warn("Revoke (delete) failed for {}: {}", discordId, err.toString()));
				}
				else {
					// The allow override isn't in cache; explicitly DENY view so they lose access anyway
					// (with @everyone already denied, an explicit member deny hides the channel).
					channel.upsertPermissionOverride(member).deny(Permission.VIEW_CHANNEL).queue(
						ok -> log.info("Revoked Discord user {} access to channel {} (denied view)",
							discordId, channelId),
						err -> log.warn("Revoke (deny) failed for {}: {}", discordId, err.toString()));
				}
			}, err -> log.warn("Revoke: retrieveMember {} failed: {}", discordId, err.toString()));
		}
		catch (Exception e) {
			log.warn("revokeAccess threw for {}: {}", discordId, e.toString());
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
			// With MemberCachePolicy.VOICE, a member currently in any voice channel is cached; null here
			// means they aren't connected to voice at all, so there's nothing to disconnect.
			Member member = guild.getMemberById(discordId);
			if (member == null) {
				log.info("Discord user {} not in voice (not cached); nothing to disconnect", discordId);
				return;
			}
			GuildVoiceState state = member.getVoiceState();
			if (state == null || state.getChannel() == null) {
				log.info("Discord user {} has no active voice channel; nothing to disconnect", discordId);
				return;
			}
			if (!channelId.equals(state.getChannel().getId())) {
				log.info("Discord user {} is in a different channel ({}); leaving them alone",
					discordId, state.getChannel().getId());
				return;
			}
			guild.kickVoiceMember(member).queue(
				ok -> log.info("Disconnected Discord user {} from channel {}", discordId, channelId),
				err -> log.warn("Voice disconnect failed for {} (does the bot have Move Members?): {}",
					discordId, err.toString()));
		}
		catch (Exception e) {
			log.warn("disconnectFromChannel threw for {}: {}", discordId, e.toString());
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

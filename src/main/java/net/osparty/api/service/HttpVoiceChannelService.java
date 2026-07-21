package net.osparty.api.service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.osparty.api.model.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class HttpVoiceChannelService implements VoiceChannelService {
	private static final Logger log = LoggerFactory.getLogger(HttpVoiceChannelService.class);

	private final RestClient http;

	public HttpVoiceChannelService(String serviceUrl, String internalToken) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(2));
		factory.setReadTimeout(Duration.ofSeconds(10));
		RestClient.Builder builder = RestClient.builder().baseUrl(serviceUrl).requestFactory(factory);
		if (internalToken != null && !internalToken.isBlank()) {
			builder = builder.defaultHeader("X-Internal-Token", internalToken);
		}
		this.http = builder.build();
		log.info("Discord voice provisioning delegated to {}", serviceUrl);
	}

	@Override
	public Optional<VoiceChannelInfo> createForParty(Party party, Collection<String> allowedDiscordIds) {
		try {
			CreateChannelResponse resp = http.post()
				.uri("/voice/channels")
				.body(new CreateChannelRequest(PartyRef.of(party),
					allowedDiscordIds == null ? List.of() : List.copyOf(allowedDiscordIds)))
				.retrieve()
				.body(CreateChannelResponse.class);
			if (resp == null || resp.channelId() == null) {
				return Optional.empty();
			}
			return Optional.of(new VoiceChannelInfo(resp.channelId(), resp.inviteUrl()));
		}
		catch (Exception e) {
			log.warn("createForParty via bot failed for party {}: {}", party.getId(), e.toString());
			return Optional.empty();
		}
	}

	@Override
	public void rename(String channelId, Party party) {
		if (channelId == null || channelId.isBlank() || party == null) {
			return;
		}
		try {
			http.post()
				.uri("/voice/channels/{id}/rename", channelId)
				.body(PartyRef.of(party))
				.retrieve()
				.toBodilessEntity();
		}
		catch (Exception e) {
			log.warn("rename via bot failed for channel {}: {}", channelId, e.toString());
		}
	}

	@Override
	public boolean grantAccess(String channelId, String discordId) {
		if (channelId == null || discordId == null) {
			return false;
		}
		try {
			http.post()
				.uri("/voice/channels/{id}/grant", channelId)
				.body(new DiscordIdRequest(discordId))
				.retrieve()
				.toBodilessEntity();
			return true;
		}
		catch (Exception e) {
			log.warn("grantAccess via bot failed for {} on {}: {}", discordId, channelId, e.toString());
			return false;
		}
	}

	@Override
	public void revokeAccess(String channelId, String discordId) {
		if (channelId == null || discordId == null) {
			return;
		}
		try {
			http.post()
				.uri("/voice/channels/{id}/revoke", channelId)
				.body(new DiscordIdRequest(discordId))
				.retrieve()
				.toBodilessEntity();
		}
		catch (Exception e) {
			log.warn("revokeAccess via bot failed for {} on {}: {}", discordId, channelId, e.toString());
		}
	}

	@Override
	public void disconnectFromChannel(String channelId, String discordId) {
		if (channelId == null || discordId == null) {
			return;
		}
		try {
			http.post()
				.uri("/voice/channels/{id}/disconnect", channelId)
				.body(new DiscordIdRequest(discordId))
				.retrieve()
				.toBodilessEntity();
		}
		catch (Exception e) {
			log.warn("disconnectFromChannel via bot failed for {} on {}: {}", discordId, channelId, e.toString());
		}
	}

	@Override
	public void delete(String channelId) {
		if (channelId == null || channelId.isBlank()) {
			return;
		}
		try {
			http.delete()
				.uri("/voice/channels/{id}", channelId)
				.retrieve()
				.toBodilessEntity();
		}
		catch (Exception e) {
			log.debug("delete via bot failed for channel {}: {}", channelId, e.toString());
		}
	}

	private record PartyRef(String id, String host, String inviteCode, String activity) {
		static PartyRef of(Party p) {
			return new PartyRef(p.getId(), p.getHost(), p.getInviteCode(), p.getActivity());
		}
	}

	private record CreateChannelRequest(PartyRef party, List<String> allowedDiscordIds) {
	}

	private record CreateChannelResponse(String channelId, String inviteUrl) {
	}

	private record DiscordIdRequest(String discordId) {
	}
}

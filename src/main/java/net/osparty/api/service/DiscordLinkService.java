package net.osparty.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DiscordLinkService {
	private static final String HASH_KEY = "discordlink:hash:";
	private static final String DISCORD_KEY = "discordlink:discord:";
	private static final String NONCE_KEY = "discordlink:nonce:";
	private static final Duration NONCE_TTL = Duration.ofMinutes(10);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final String clientId;
	private final String redirectUri;

	public DiscordLinkService(StringRedisTemplate redis, ObjectMapper mapper,
		@Value("${app.discord.oauth.client-id:}") String clientId,
		@Value("${app.discord.oauth.redirect-uri:}") String redirectUri) {
		this.redis = redis;
		this.mapper = mapper;
		this.clientId = clientId == null ? "" : clientId.trim();
		this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
	}

	public boolean isEnabled() {
		return !clientId.isBlank() && !redirectUri.isBlank();
	}

	public String beginLink(long accountHash) {
		String nonce = newNonce();
		redis.opsForValue().set(NONCE_KEY + nonce, Long.toString(accountHash), NONCE_TTL);
		String redirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
		return "https://discord.com/oauth2/authorize?client_id=" + clientId
			+ "&redirect_uri=" + redirect
			+ "&response_type=code&scope=identify&state=" + nonce;
	}

	public Optional<Long> consumeNonce(String nonce) {
		if (nonce == null || nonce.isBlank()) {
			return Optional.empty();
		}
		String key = NONCE_KEY + nonce;
		String hash = redis.opsForValue().get(key);
		if (hash == null) {
			return Optional.empty();
		}
		redis.delete(key);
		try {
			return Optional.of(Long.parseLong(hash));
		}
		catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	public void link(long accountHash, String discordId, String username) {
		Link link = new Link(discordId, username);
		try {
			redis.opsForValue().set(HASH_KEY + accountHash, mapper.writeValueAsString(link));
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to serialise Discord link", e);
		}
		redis.opsForValue().set(DISCORD_KEY + discordId, Long.toString(accountHash));
	}

	public void unlink(long accountHash) {
		String json = redis.opsForValue().get(HASH_KEY + accountHash);
		if (json != null) {
			try {
				Link link = mapper.readValue(json, Link.class);
				if (link.discordId() != null) {
					redis.delete(DISCORD_KEY + link.discordId());
				}
			}
			catch (Exception ignored) {
			}
		}
		redis.delete(HASH_KEY + accountHash);
	}

	public Optional<Link> getByAccountHash(long accountHash) {
		String json = redis.opsForValue().get(HASH_KEY + accountHash);
		if (json == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(mapper.readValue(json, Link.class));
		}
		catch (Exception e) {
			return Optional.empty();
		}
	}

	public Optional<String> discordIdForAccountHash(long accountHash) {
		return getByAccountHash(accountHash).map(Link::discordId);
	}

	public Map<Long, String> discordIdsForAccountHashes(Collection<Long> accountHashes) {
		if (accountHashes == null || accountHashes.isEmpty()) {
			return Map.of();
		}
		List<Long> ordered = new ArrayList<>(accountHashes);
		List<String> keys = new ArrayList<>(ordered.size());
		for (Long hash : ordered) {
			keys.add(HASH_KEY + hash);
		}
		List<String> values = redis.opsForValue().multiGet(keys);
		Map<Long, String> out = new HashMap<>();
		if (values == null) {
			return out;
		}
		for (int i = 0; i < values.size(); i++) {
			String json = values.get(i);
			if (json == null) {
				continue;
			}
			try {
				Link link = mapper.readValue(json, Link.class);
				if (link.discordId() != null) {
					out.put(ordered.get(i), link.discordId());
				}
			}
			catch (Exception ignored) {
			}
		}
		return out;
	}

	private static String newNonce() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public record Link(String discordId, String username) {
	}
}

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.osparty.api.repository.DiscordLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * OSRS-account &harr; Discord-account links.
 *
 * <h2>Postgres writes, Redis reads</h2>
 * A link is a record, not a cache: each one was won through an OAuth round-trip, and losing the set
 * would force every user to re-link. Postgres is therefore authoritative, and every write goes
 * there first.
 *
 * <p>Reads deliberately do not. {@link #discordIdsForAccountHashes} is called for every member of
 * every party on every reconcile tick, so it stays on a Redis mirror written alongside each
 * Postgres write and warmed from Postgres at startup. That makes the mirror rebuildable rather than
 * precious: a Redis flush now costs a warm-up, not everyone's link.
 *
 * <p>The nonce for an in-flight OAuth handshake stays Redis-only. It lives ten minutes and is
 * meaningless afterwards, which is exactly what a TTL cache is for.
 */
@Service
public class DiscordLinkService {
	private static final Logger log = LoggerFactory.getLogger(DiscordLinkService.class);

	private static final String HASH_KEY = "discordlink:hash:";
	private static final String NONCE_KEY = "discordlink:nonce:";
	private static final Duration NONCE_TTL = Duration.ofMinutes(10);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final DiscordLinkRepository repository;
	private final String clientId;
	private final String redirectUri;

	public DiscordLinkService(StringRedisTemplate redis, ObjectMapper mapper,
		DiscordLinkRepository repository,
		@Value("${app.discord.oauth.client-id:}") String clientId,
		@Value("${app.discord.oauth.redirect-uri:}") String redirectUri) {
		this.redis = redis;
		this.mapper = mapper;
		this.repository = repository;
		this.clientId = clientId == null ? "" : clientId.trim();
		this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
	}

	public boolean isEnabled() {
		return !clientId.isBlank() && !redirectUri.isBlank();
	}

	public String beginLink(long accountHash) {
		String nonce = newNonce();
		redis.opsForValue().set(NONCE_KEY + nonce, Long.toString(accountHash), NONCE_TTL);
		return authorizeUrl(nonce);
	}

	/**
	 * Where to send a browser to have Discord identify its user, carrying {@code state} back to us.
	 *
	 * <p>Split out of {@link #beginLink} for {@link DiscordRecoveryService}, which needs the same URL with a
	 * state of its own -- the two flows share one registered redirect URI and are told apart by the state.
	 */
	public String authorizeUrl(String state) {
		String redirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
		return "https://discord.com/oauth2/authorize?client_id=" + clientId
			+ "&redirect_uri=" + redirect
			+ "&response_type=code&scope=identify&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
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

	/**
	 * Binds an OSRS account to a Discord account, replacing any previous binding for that account.
	 *
	 * @param verified whether the session that asked for this link had proved it was that account. Only a
	 *     verified link is ever accepted as proof of ownership by {@link DiscordRecoveryService} -- an
	 *     unverified one says nothing more than that somebody named the account hash.
	 */
	public void link(long accountHash, String discordId, String username, boolean verified) {
		repository.link(accountHash, discordId, username, verified);
		mirror(accountHash, new Link(discordId, username));
	}

	/**
	 * Whether this account's Discord link was made from a session that had proved it was this account.
	 *
	 * <p>Straight to Postgres: the Redis mirror carries only what the badge path needs, and this is asked
	 * once per recovery attempt rather than once per party member per tick.
	 */
	public boolean hasVerifiedLink(long accountHash) {
		return repository.findByAccountHash(accountHash)
			.map(DiscordLinkRepository.Link::verified)
			.orElse(false);
	}

	public void unlink(long accountHash) {
		repository.unlink(accountHash);
		try {
			redis.delete(HASH_KEY + accountHash);
		}
		catch (Exception e) {
			// The mirror is rebuildable; Postgres has already forgotten the link.
			log.debug("Failed to clear link mirror for {}: {}", accountHash, e.toString());
		}
	}

	/**
	 * All OSRS accounts currently linked to a given Discord user (empty when none). Served from
	 * Postgres: this is a rare admin-shaped query, not part of the broadcast path, and an index on
	 * {@code discord_link.discord_id} does the job the old hand-maintained Redis set used to.
	 */
	public Set<Long> accountHashesForDiscordId(String discordId) {
		if (discordId == null || discordId.isBlank()) {
			return Set.of();
		}
		return new HashSet<>(repository.accountHashesFor(discordId));
	}

	/**
	 * Reads through the Redis mirror, falling back to Postgres on a miss and re-warming as it goes,
	 * so a flushed or partially-warmed mirror self-heals instead of silently reporting "not linked".
	 */
	public Optional<Link> getByAccountHash(long accountHash) {
		try {
			String json = redis.opsForValue().get(HASH_KEY + accountHash);
			if (json != null) {
				return Optional.of(mapper.readValue(json, Link.class));
			}
		}
		catch (Exception e) {
			log.debug("Link mirror read failed for {}, falling back to Postgres: {}",
				accountHash, e.toString());
		}
		Optional<Link> stored = repository.findByAccountHash(accountHash)
			.map(row -> new Link(row.discordId(), row.discordName()));
		stored.ifPresent(link -> mirror(accountHash, link));
		return stored;
	}

	/** Writes one binding into the Redis mirror. Failures are logged, never propagated. */
	private void mirror(long accountHash, Link link) {
		try {
			redis.opsForValue().set(HASH_KEY + accountHash, mapper.writeValueAsString(link));
		}
		catch (Exception e) {
			log.debug("Failed to mirror link for {}: {}", accountHash, e.toString());
		}
	}

	/** Bulk mirror write, used to warm Redis from Postgres at startup. */
	public void mirrorAll(List<DiscordLinkRepository.Link> rows) {
		for (DiscordLinkRepository.Link row : rows) {
			mirror(row.accountHash(), new Link(row.discordId(), row.discordName()));
		}
	}

	public Optional<String> discordIdForAccountHash(long accountHash) {
		return getByAccountHash(accountHash).map(Link::discordId);
	}

	/**
	 * Bulk lookup on the broadcast hot path — called for every party member on every reconcile tick.
	 * Served entirely from the Redis mirror with a single MGET; deliberately no Postgres fallback,
	 * because a per-tick SQL round trip per member is exactly what the mirror exists to avoid. A
	 * cold mirror degrades to "no badges", which the startup warm and the read-through in
	 * {@link #getByAccountHash} repair.
	 */
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

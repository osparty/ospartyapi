package net.osparty.api.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
import net.osparty.api.service.PartyFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class RedisPartyRepository implements PartyRepository {
	private static final Logger log = LoggerFactory.getLogger(RedisPartyRepository.class);

	private static final String PARTY_KEY = "party:";
	private static final String HOST_KEY = "partyhost:";
	private static final String CODE_KEY = "partycode:";
	private static final String CREDENTIAL_KEY = "partykey:";
	private static final String SEQ_KEY = "party:seq";
	private static final String INDEX_KEY = "party:ids";

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final Duration ttl;

	public RedisPartyRepository(StringRedisTemplate redis, ObjectMapper mapper,
		@Value("${app.ads.ttl-ms:90000}") long ttlMs) {
		this.redis = redis;
		this.mapper = mapper;
		this.ttl = Duration.ofMillis(ttlMs);
		log.info("Using Redis party storage (ttl {}s)", ttl.toSeconds());
	}

	@Override
	public List<Party> list(String activity) {
		Set<String> ids = redis.opsForSet().members(INDEX_KEY);
		if (ids == null || ids.isEmpty()) {
			return new ArrayList<>();
		}
		List<String> idList = new ArrayList<>(ids);
		List<String> keys = new ArrayList<>(idList.size());
		for (String id : idList) {
			keys.add(PARTY_KEY + id);
		}
		List<String> values = redis.opsForValue().multiGet(keys);

		List<Party> out = new ArrayList<>();
		List<Object> expired = new ArrayList<>();
		for (int i = 0; i < idList.size(); i++) {
			String json = values == null ? null : values.get(i);
			if (json == null) {
				expired.add(idList.get(i));
				continue;
			}
			Party party = parse(json, keys.get(i));
			if (party != null && !party.isPrivateParty() && (activity == null || activity.isBlank()
				|| activity.equals(party.getActivity()))) {
				out.add(party);
			}
		}
		if (!expired.isEmpty()) {
			redis.opsForSet().remove(INDEX_KEY, expired.toArray());
		}
		out.sort(Comparator.comparingLong(Party::getCreatedAt).reversed());
		return out;
	}

	@Override
	public Optional<Party> findById(String id) {
		return id == null ? Optional.empty() : Optional.ofNullable(read(PARTY_KEY + id));
	}

	@Override
	public Optional<Party> findByInviteCode(String code) {
		String normalized = PartyFactory.normalizeInviteCode(code);
		if (normalized == null) {
			return Optional.empty();
		}
		String id = redis.opsForValue().get(CODE_KEY + normalized);
		return id == null ? Optional.empty() : Optional.ofNullable(read(PARTY_KEY + id));
	}

	@Override
	public Optional<Party> findByHost(String host) {
		if (host == null) {
			return Optional.empty();
		}
		String id = redis.opsForValue().get(HOST_KEY + PartyFactory.normalizeHost(host));
		return id == null ? Optional.empty() : Optional.ofNullable(read(PARTY_KEY + id));
	}

	@Override
	public Party create(PartyRequest request, String hostKey) {
		long now = System.currentTimeMillis();
		String hostIndexKey = HOST_KEY + PartyFactory.normalizeHost(request.host());

		String previousId = redis.opsForValue().get(hostIndexKey);
		if (previousId != null) {
			Party previous = read(PARTY_KEY + previousId);
			String previousCode = previous == null ? null : previous.getInviteCode();
			redis.executePipelined(new SessionCallback<Object>() {
				@Override
				@SuppressWarnings({"unchecked", "rawtypes"})
				public Object execute(RedisOperations operations) {
					if (previousCode != null) {
						operations.delete(CODE_KEY + previousCode);
					}
					operations.delete(PARTY_KEY + previousId);
					operations.delete(CREDENTIAL_KEY + previousId);
					operations.opsForSet().remove(INDEX_KEY, previousId);
					return null;
				}
			});
		}

		String id = String.valueOf(redis.opsForValue().increment(SEQ_KEY));
		String inviteCode = uniqueInviteCode();
		Party party = PartyFactory.fromRequest(request, id, inviteCode, now);
		String json = write(party);

		redis.executePipelined(new SessionCallback<Object>() {
			@Override
			@SuppressWarnings({"unchecked", "rawtypes"})
			public Object execute(RedisOperations operations) {
				operations.opsForValue().set(PARTY_KEY + id, json, ttl);
				operations.opsForSet().add(INDEX_KEY, id);
				operations.opsForValue().set(hostIndexKey, id, ttl);
				operations.opsForValue().set(CODE_KEY + inviteCode, id, ttl);
				if (hostKey != null && !hostKey.isBlank()) {
					operations.opsForValue().set(CREDENTIAL_KEY + id, hostKey, ttl);
				}
				return null;
			}
		});
		return party;
	}

	@Override
	public Authorization authorize(String id, String hostKey) {
		if (read(PARTY_KEY + id) == null) {
			return Authorization.NOT_FOUND;
		}
		String stored = redis.opsForValue().get(CREDENTIAL_KEY + id);
		return PartyFactory.hostKeyAuthorized(stored, hostKey)
			? Authorization.OK : Authorization.FORBIDDEN;
	}

	@Override
	public Optional<Party> update(String id, PartyUpdate patch) {
		String key = PARTY_KEY + id;
		Party party = read(key);
		if (party == null) {
			return Optional.empty();
		}
		boolean changed = PartyFactory.applyUpdate(party, patch);
		if (changed) {
			redis.opsForValue().set(key, write(party), ttl);
		}
		else {
			redis.expire(key, ttl);
		}
		redis.expire(HOST_KEY + PartyFactory.normalizeHost(party.getHost()), ttl);
		redis.expire(CREDENTIAL_KEY + id, ttl);
		if (party.getInviteCode() != null) {
			redis.expire(CODE_KEY + party.getInviteCode(), ttl);
		}
		return Optional.of(party);
	}

	@Override
	public Optional<Party> transferHost(String id, String newHost, String newKey) {
		String key = PARTY_KEY + id;
		Party party = read(key);
		if (party == null) {
			return Optional.empty();
		}
		String oldHostIndex = HOST_KEY + PartyFactory.normalizeHost(party.getHost());
		String newHostIndex = HOST_KEY + PartyFactory.normalizeHost(newHost);
		party.setHost(newHost);
		String json = write(party);
		redis.executePipelined(new SessionCallback<Object>() {
			@Override
			@SuppressWarnings({"unchecked", "rawtypes"})
			public Object execute(RedisOperations operations) {
				if (!oldHostIndex.equals(newHostIndex)) {
					operations.delete(oldHostIndex);
				}
				operations.opsForValue().set(newHostIndex, id, ttl);
				operations.opsForValue().set(CREDENTIAL_KEY + id, newKey, ttl);
				operations.opsForValue().set(key, json, ttl);
				if (party.getInviteCode() != null) {
					operations.expire(CODE_KEY + party.getInviteCode(), ttl);
				}
				return null;
			}
		});
		return Optional.of(party);
	}

	@Override
	public Optional<Party> attachVoiceChannel(String id, String channelId, String inviteUrl) {
		String key = PARTY_KEY + id;
		Party party = read(key);
		if (party == null) {
			return Optional.empty();
		}
		party.setDiscordChannelId(channelId);
		party.setDiscordInviteUrl(inviteUrl);
		Long remaining = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
		Duration ttlToUse = (remaining != null && remaining > 0) ? Duration.ofMillis(remaining) : ttl;
		redis.opsForValue().set(key, write(party), ttlToUse);
		return Optional.of(party);
	}

	@Override
	public Optional<Party> delete(String id) {
		String key = PARTY_KEY + id;
		Party party = read(key);
		if (party == null) {
			return Optional.empty();
		}
		redis.delete(key);
		redis.opsForSet().remove(INDEX_KEY, id);
		redis.delete(HOST_KEY + PartyFactory.normalizeHost(party.getHost()));
		redis.delete(CREDENTIAL_KEY + id);
		if (party.getInviteCode() != null) {
			redis.delete(CODE_KEY + party.getInviteCode());
		}
		return Optional.of(party);
	}

	private String uniqueInviteCode() {
		String code;
		do {
			code = PartyFactory.newInviteCode();
		}
		while (redis.hasKey(CODE_KEY + code));
		return code;
	}

	private Party read(String key) {
		return parse(redis.opsForValue().get(key), key);
	}

	private Party parse(String json, String keyForLog) {
		if (json == null) {
			return null;
		}
		try {
			return mapper.readValue(json, Party.class);
		}
		catch (Exception e) {
			log.warn("Failed to read party at {}", keyForLog, e);
			return null;
		}
	}

	private String write(Party party) {
		try {
			return mapper.writeValueAsString(party);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to serialise party " + party.getId(), e);
		}
	}
}

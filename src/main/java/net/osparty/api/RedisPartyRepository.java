package net.osparty.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed ad store — the production storage. Survives API restarts and uses
 * Redis' <b>native key expiry</b> as the liveness/TTL mechanism: an ad is written
 * with a TTL, the host keeps it fresh (the open WebSocket, or the legacy heartbeat),
 * and Redis evicts it automatically when the host goes quiet.
 *
 * <p>Keys: {@code party:{id}} -> JSON, {@code partyhost:{normHost}} -> id (a
 * secondary index enforcing one ad per host), {@code party:seq} -> id counter.
 *
 * <p>{@code @Profile("!test")} so the test suite uses an in-memory fake instead of
 * requiring a real Redis.
 */
@Repository
@Profile("!test")
public class RedisPartyRepository implements PartyRepository
{
	private static final Logger log = LoggerFactory.getLogger(RedisPartyRepository.class);

	private static final String PARTY_KEY = "party:";
	private static final String HOST_KEY = "partyhost:";
	private static final String CODE_KEY = "partycode:";
	/** Per-party host credential (the session secret), kept under the same TTL as the ad. */
	private static final String CREDENTIAL_KEY = "partykey:";
	private static final String SEQ_KEY = "party:seq";

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final Duration ttl;

	public RedisPartyRepository(StringRedisTemplate redis, ObjectMapper mapper,
		@Value("${app.ads.ttl-ms:90000}") long ttlMs)
	{
		this.redis = redis;
		this.mapper = mapper;
		this.ttl = Duration.ofMillis(ttlMs);
		log.info("Using Redis party storage (ttl {}s)", ttl.toSeconds());
	}

	@Override
	public List<Party> list(String activity)
	{
		Set<String> keys = redis.keys(PARTY_KEY + "*");
		List<Party> out = new ArrayList<>();
		if (keys != null)
		{
			for (String key : keys)
			{
				if (key.equals(SEQ_KEY))
				{
					continue;
				}
				Party party = read(key);
				if (party != null && !party.isPrivateParty() && (activity == null || activity.isBlank()
					|| activity.equals(party.getActivity())))
				{
					out.add(party);
				}
			}
		}
		out.sort(Comparator.comparingLong(Party::getCreatedAt).reversed());
		return out;
	}

	@Override
	public Optional<Party> findByInviteCode(String code)
	{
		String normalized = PartyFactory.normalizeInviteCode(code);
		if (normalized == null)
		{
			return Optional.empty();
		}
		String id = redis.opsForValue().get(CODE_KEY + normalized);
		return id == null ? Optional.empty() : Optional.ofNullable(read(PARTY_KEY + id));
	}

	@Override
	public Optional<Party> findByHost(String host)
	{
		if (host == null)
		{
			return Optional.empty();
		}
		String id = redis.opsForValue().get(HOST_KEY + PartyFactory.normalizeHost(host));
		return id == null ? Optional.empty() : Optional.ofNullable(read(PARTY_KEY + id));
	}

	@Override
	public Party create(PartyRequest request, String hostKey)
	{
		long now = System.currentTimeMillis();
		String hostIndexKey = HOST_KEY + PartyFactory.normalizeHost(request.host());

		// One ad per host: drop the host's previous ad (if any) before writing.
		String previousId = redis.opsForValue().get(hostIndexKey);
		if (previousId != null)
		{
			Party previous = read(PARTY_KEY + previousId);
			if (previous != null && previous.getInviteCode() != null)
			{
				redis.delete(CODE_KEY + previous.getInviteCode());
			}
			redis.delete(PARTY_KEY + previousId);
			redis.delete(CREDENTIAL_KEY + previousId);
		}

		String id = String.valueOf(redis.opsForValue().increment(SEQ_KEY));
		String inviteCode = uniqueInviteCode();
		Party party = PartyFactory.fromRequest(request, id, inviteCode, now);

		redis.opsForValue().set(PARTY_KEY + id, write(party), ttl);
		redis.opsForValue().set(hostIndexKey, id, ttl);
		redis.opsForValue().set(CODE_KEY + inviteCode, id, ttl);
		if (hostKey != null && !hostKey.isBlank())
		{
			redis.opsForValue().set(CREDENTIAL_KEY + id, hostKey, ttl);
		}
		return party;
	}

	@Override
	public Authorization authorize(String id, String hostKey)
	{
		if (read(PARTY_KEY + id) == null)
		{
			return Authorization.NOT_FOUND;
		}
		String stored = redis.opsForValue().get(CREDENTIAL_KEY + id);
		return PartyFactory.hostKeyAuthorized(stored, hostKey)
			? Authorization.OK : Authorization.FORBIDDEN;
	}

	@Override
	public Optional<Party> update(String id, PartyUpdate patch)
	{
		String key = PARTY_KEY + id;
		Party party = read(key);
		if (party == null)
		{
			return Optional.empty();
		}
		// Apply changed fields (only rewrite when something actually changed); always
		// refresh the TTL — an update/touch means the host is alive. With the socket as
		// the heartbeat, an empty patch is exactly that liveness refresh.
		boolean changed = PartyFactory.applyUpdate(party, patch);
		if (changed)
		{
			redis.opsForValue().set(key, write(party), ttl);
		}
		else
		{
			redis.expire(key, ttl);
		}
		redis.expire(HOST_KEY + PartyFactory.normalizeHost(party.getHost()), ttl);
		redis.expire(CREDENTIAL_KEY + id, ttl);
		if (party.getInviteCode() != null)
		{
			redis.expire(CODE_KEY + party.getInviteCode(), ttl);
		}
		return Optional.of(party);
	}

	@Override
	public Optional<Party> delete(String id)
	{
		String key = PARTY_KEY + id;
		Party party = read(key);
		if (party == null)
		{
			return Optional.empty();
		}
		redis.delete(key);
		redis.delete(HOST_KEY + PartyFactory.normalizeHost(party.getHost()));
		redis.delete(CREDENTIAL_KEY + id);
		if (party.getInviteCode() != null)
		{
			redis.delete(CODE_KEY + party.getInviteCode());
		}
		return Optional.of(party);
	}

	private String uniqueInviteCode()
	{
		String code;
		do
		{
			code = PartyFactory.newInviteCode();
		}
		while (redis.hasKey(CODE_KEY + code));
		return code;
	}

	private Party read(String key)
	{
		String json = redis.opsForValue().get(key);
		if (json == null)
		{
			return null;
		}
		try
		{
			return mapper.readValue(json, Party.class);
		}
		catch (Exception e)
		{
			log.warn("Failed to read party at {}", key, e);
			return null;
		}
	}

	private String write(Party party)
	{
		try
		{
			return mapper.writeValueAsString(party);
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Failed to serialise party " + party.getId(), e);
		}
	}
}

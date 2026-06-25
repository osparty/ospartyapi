package net.osparty.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed ad store (selected with {@code app.storage=redis}). Survives API
 * restarts and uses Redis' <b>native key expiry</b> as the liveness/TTL
 * mechanism: an ad is written with a TTL, the host's heartbeat refreshes it, and
 * Redis evicts it automatically when the host goes quiet — so the scheduled
 * evictor is a no-op here.
 *
 * <p>Keys: {@code party:{id}} -> JSON, {@code partyhost:{normHost}} -> id (a
 * secondary index enforcing one ad per host), {@code party:seq} -> id counter.
 */
@Repository
@ConditionalOnProperty(name = "app.storage", havingValue = "redis")
public class RedisPartyRepository implements PartyRepository
{
	private static final Logger log = LoggerFactory.getLogger(RedisPartyRepository.class);

	private static final String PARTY_KEY = "party:";
	private static final String HOST_KEY = "partyhost:";
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
				if (party != null && (activity == null || activity.isBlank()
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
	public Party create(PartyRequest request)
	{
		long now = System.currentTimeMillis();
		String hostKey = HOST_KEY + PartyFactory.normalizeHost(request.host());

		// One ad per host: drop the host's previous ad (if any) before writing.
		String previousId = redis.opsForValue().get(hostKey);
		if (previousId != null)
		{
			redis.delete(PARTY_KEY + previousId);
		}

		String id = String.valueOf(redis.opsForValue().increment(SEQ_KEY));
		Party party = PartyFactory.fromRequest(request, id, now);

		redis.opsForValue().set(PARTY_KEY + id, write(party), ttl);
		redis.opsForValue().set(hostKey, id, ttl);
		return party;
	}

	@Override
	public Optional<Party> heartbeat(String id)
	{
		String key = PARTY_KEY + id;
		Party party = read(key);
		if (party == null)
		{
			return Optional.empty();
		}
		// Native TTL refresh — this is the whole liveness mechanism.
		redis.expire(key, ttl);
		redis.expire(HOST_KEY + PartyFactory.normalizeHost(party.getHost()), ttl);
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
		return Optional.of(party);
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

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
	private static final String CODE_KEY = "partycode:";
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
	public Party create(PartyRequest request)
	{
		long now = System.currentTimeMillis();
		String hostKey = HOST_KEY + PartyFactory.normalizeHost(request.host());

		// One ad per host: drop the host's previous ad (if any) before writing.
		String previousId = redis.opsForValue().get(hostKey);
		if (previousId != null)
		{
			Party previous = read(PARTY_KEY + previousId);
			if (previous != null && previous.getInviteCode() != null)
			{
				redis.delete(CODE_KEY + previous.getInviteCode());
			}
			redis.delete(PARTY_KEY + previousId);
		}

		String id = String.valueOf(redis.opsForValue().increment(SEQ_KEY));
		String inviteCode = uniqueInviteCode();
		Party party = PartyFactory.fromRequest(request, id, inviteCode, now);

		redis.opsForValue().set(PARTY_KEY + id, write(party), ttl);
		redis.opsForValue().set(hostKey, id, ttl);
		redis.opsForValue().set(CODE_KEY + inviteCode, id, ttl);
		return party;
	}

	@Override
	public Optional<Party> heartbeat(String id, Integer size, String world)
	{
		String key = PARTY_KEY + id;
		Party party = read(key);
		if (party == null)
		{
			return Optional.empty();
		}
		// Report current occupancy and the host's live world (both are peer-to-peer;
		// the host tells us). Only rewrite the value when something actually changed.
		boolean changed = false;
		if (size != null && size > 0 && size != party.getSize())
		{
			party.setSize(size);
			changed = true;
		}
		if (world != null && !world.isBlank() && !world.equals(party.getWorld()))
		{
			party.setWorld(world);
			changed = true;
		}
		if (changed)
		{
			redis.opsForValue().set(key, write(party), ttl);
		}
		else
		{
			// Native TTL refresh — this is the whole liveness mechanism.
			redis.expire(key, ttl);
		}
		redis.expire(HOST_KEY + PartyFactory.normalizeHost(party.getHost()), ttl);
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

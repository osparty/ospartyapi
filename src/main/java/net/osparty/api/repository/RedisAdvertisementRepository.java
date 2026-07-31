package net.osparty.api.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.osparty.api.model.Advertisement;
import net.osparty.api.model.AdvertisementRequest;
import net.osparty.api.model.AdvertisementUpdate;
import net.osparty.api.service.AdvertisementFactory;
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
public class RedisAdvertisementRepository implements AdvertisementRepository {
	private static final Logger log = LoggerFactory.getLogger(RedisAdvertisementRepository.class);

	/**
	 * The key strings still say {@code party}. They are storage, not source: renaming them strands every
	 * ad currently in Redis, and {@code partykey:} holds the host credentials — a host would lose control
	 * of its own advertisement until the 90s TTL expired and it re-hosted. Nothing is bought by changing
	 * them, so only the constant names moved.
	 */
	private static final String AD_KEY = "party:";
	private static final String HOST_KEY = "partyhost:";
	private static final String CODE_KEY = "partycode:";
	private static final String CREDENTIAL_KEY = "partykey:";
	private static final String SEQ_KEY = "party:seq";
	/**
	 * Allocates {@link Advertisement#getSeq()}. Separate from the id sequence: ids are handed out once per ad,
	 * revisions on every meaningful edit of one.
	 */
	private static final String REV_KEY = "party:rev";
	private static final String INDEX_KEY = "party:ids";

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final Duration ttl;

	public RedisAdvertisementRepository(StringRedisTemplate redis, ObjectMapper mapper,
		@Value("${app.ads.ttl-ms:90000}") long ttlMs) {
		this.redis = redis;
		this.mapper = mapper;
		this.ttl = Duration.ofMillis(ttlMs);
		log.info("Using Redis advertisement storage (ttl {}s)", ttl.toSeconds());
	}

	@Override
	public List<Advertisement> list(String activity) {
		Set<String> ids = redis.opsForSet().members(INDEX_KEY);
		if (ids == null || ids.isEmpty()) {
			return new ArrayList<>();
		}
		List<String> idList = new ArrayList<>(ids);
		List<String> keys = new ArrayList<>(idList.size());
		for (String id : idList) {
			keys.add(AD_KEY + id);
		}
		List<String> values = redis.opsForValue().multiGet(keys);

		List<Advertisement> out = new ArrayList<>();
		List<Object> expired = new ArrayList<>();
		for (int i = 0; i < idList.size(); i++) {
			String json = values == null ? null : values.get(i);
			if (json == null) {
				expired.add(idList.get(i));
				continue;
			}
			Advertisement ad = parse(json, keys.get(i));
			if (ad != null && !ad.isPrivateAd() && (activity == null || activity.isBlank()
				|| activity.equals(ad.getActivity()))) {
				out.add(ad);
			}
		}
		if (!expired.isEmpty()) {
			redis.opsForSet().remove(INDEX_KEY, expired.toArray());
		}
		out.sort(Comparator.comparingLong(Advertisement::getCreatedAt).reversed());
		return out;
	}

	@Override
	public int advertisementCount() {
		Long size = redis.opsForSet().size(INDEX_KEY);
		return size == null ? 0 : size.intValue();
	}

	@Override
	public Optional<Advertisement> findById(String id) {
		return id == null ? Optional.empty() : Optional.ofNullable(read(AD_KEY + id));
	}

	@Override
	public Optional<Advertisement> findByInviteCode(String code) {
		String normalized = AdvertisementFactory.normalizeInviteCode(code);
		if (normalized == null) {
			return Optional.empty();
		}
		String id = redis.opsForValue().get(CODE_KEY + normalized);
		return id == null ? Optional.empty() : Optional.ofNullable(read(AD_KEY + id));
	}

	@Override
	public Optional<Advertisement> findByHost(String host) {
		if (host == null) {
			return Optional.empty();
		}
		String id = redis.opsForValue().get(HOST_KEY + AdvertisementFactory.normalizeHost(host));
		return id == null ? Optional.empty() : Optional.ofNullable(read(AD_KEY + id));
	}

	@Override
	public Advertisement create(AdvertisementRequest request, String hostKey) {
		long now = System.currentTimeMillis();
		String hostIndexKey = HOST_KEY + AdvertisementFactory.normalizeHost(request.host());

		String previousId = redis.opsForValue().get(hostIndexKey);
		if (previousId != null) {
			Advertisement previous = read(AD_KEY + previousId);
			String previousCode = previous == null ? null : previous.getInviteCode();
			redis.executePipelined(new SessionCallback<Object>() {
				@Override
				@SuppressWarnings({"unchecked", "rawtypes"})
				public Object execute(RedisOperations operations) {
					if (previousCode != null) {
						operations.delete(CODE_KEY + previousCode);
					}
					operations.delete(AD_KEY + previousId);
					operations.delete(CREDENTIAL_KEY + previousId);
					operations.opsForSet().remove(INDEX_KEY, previousId);
					return null;
				}
			});
		}

		String id = String.valueOf(redis.opsForValue().increment(SEQ_KEY));
		String inviteCode = uniqueInviteCode();
		Advertisement ad = AdvertisementFactory.fromRequest(request, id, inviteCode, now);
		ad.setSeq(nextRevision());
		String json = write(ad);

		redis.executePipelined(new SessionCallback<Object>() {
			@Override
			@SuppressWarnings({"unchecked", "rawtypes"})
			public Object execute(RedisOperations operations) {
				operations.opsForValue().set(AD_KEY + id, json, ttl);
				operations.opsForSet().add(INDEX_KEY, id);
				operations.opsForValue().set(hostIndexKey, id, ttl);
				operations.opsForValue().set(CODE_KEY + inviteCode, id, ttl);
				if (hostKey != null && !hostKey.isBlank()) {
					operations.opsForValue().set(CREDENTIAL_KEY + id, hostKey, ttl);
				}
				return null;
			}
		});
		return ad;
	}

	@Override
	public Authorization authorize(String id, String hostKey) {
		if (read(AD_KEY + id) == null) {
			return Authorization.NOT_FOUND;
		}
		String stored = redis.opsForValue().get(CREDENTIAL_KEY + id);
		return AdvertisementFactory.hostKeyAuthorized(stored, hostKey)
			? Authorization.OK : Authorization.FORBIDDEN;
	}

	@Override
	public Optional<Advertisement> update(String id, AdvertisementUpdate patch) {
		String key = AD_KEY + id;
		Advertisement ad = read(key);
		if (ad == null) {
			return Optional.empty();
		}
		boolean changed = AdvertisementFactory.applyUpdate(ad, patch);
		if (changed) {
			// Deliberately only when something actually moved. A heartbeat that changes nothing must not
			// advance the revision, or after one interval every ad on the board looks new to a resuming
			// client and the resume degrades into a full snapshot wearing a different name.
			ad.setSeq(nextRevision());
			redis.opsForValue().set(key, write(ad), ttl);
		}
		else {
			redis.expire(key, ttl);
		}
		redis.expire(HOST_KEY + AdvertisementFactory.normalizeHost(ad.getHost()), ttl);
		redis.expire(CREDENTIAL_KEY + id, ttl);
		if (ad.getInviteCode() != null) {
			redis.expire(CODE_KEY + ad.getInviteCode(), ttl);
		}
		return Optional.of(ad);
	}

	@Override
	public Optional<Advertisement> transferHost(String id, String newHost, String newKey) {
		String key = AD_KEY + id;
		Advertisement ad = read(key);
		if (ad == null) {
			return Optional.empty();
		}
		String oldHostIndex = HOST_KEY + AdvertisementFactory.normalizeHost(ad.getHost());
		String newHostIndex = HOST_KEY + AdvertisementFactory.normalizeHost(newHost);
		ad.setHost(newHost);
		// Re-point the host account hash at whoever is taking over, falling back to 0 when the new
		// host is not an admitted member with a known hash. Leaving the old host's hash in place
		// would attribute the ad -- and any ban on it -- to someone who no longer runs it.
		ad.setHostAccountHash(AdvertisementFactory.accountHashOf(ad, newHost));
		ad.setSeq(nextRevision());
		String json = write(ad);
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
				if (ad.getInviteCode() != null) {
					operations.expire(CODE_KEY + ad.getInviteCode(), ttl);
				}
				return null;
			}
		});
		return Optional.of(ad);
	}

	@Override
	public Optional<Advertisement> attachVoiceChannel(String id, String channelId, String inviteUrl) {
		String key = AD_KEY + id;
		Advertisement ad = read(key);
		if (ad == null) {
			return Optional.empty();
		}
		ad.setDiscordChannelId(channelId);
		ad.setDiscordInviteUrl(inviteUrl);
		ad.setSeq(nextRevision());
		Long remaining = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
		Duration ttlToUse = (remaining != null && remaining > 0) ? Duration.ofMillis(remaining) : ttl;
		redis.opsForValue().set(key, write(ad), ttlToUse);
		return Optional.of(ad);
	}

	@Override
	public Optional<Advertisement> delete(String id) {
		String key = AD_KEY + id;
		Advertisement ad = read(key);
		if (ad == null) {
			return Optional.empty();
		}
		redis.delete(key);
		redis.opsForSet().remove(INDEX_KEY, id);
		redis.delete(HOST_KEY + AdvertisementFactory.normalizeHost(ad.getHost()));
		redis.delete(CREDENTIAL_KEY + id);
		if (ad.getInviteCode() != null) {
			redis.delete(CODE_KEY + ad.getInviteCode());
		}
		return Optional.of(ad);
	}

	/**
	 * The next cluster-wide revision. One INCR, on a path that is already writing to Redis.
	 *
	 * <p>Falls back to the wall clock if Redis declines to answer, which keeps the number monotonic enough
	 * to be useful without making a write fail over a bookkeeping value. A resuming client that lands on
	 * the wrong side of such a gap is sent a full board, which is what it would have got anyway.
	 */
	@Override
	public long nextRevision() {
		Long next = redis.opsForValue().increment(REV_KEY);
		return next == null ? System.currentTimeMillis() : next;
	}

	private String uniqueInviteCode() {
		String code;
		do {
			code = AdvertisementFactory.newInviteCode();
		}
		while (redis.hasKey(CODE_KEY + code));
		return code;
	}

	private Advertisement read(String key) {
		return parse(redis.opsForValue().get(key), key);
	}

	private Advertisement parse(String json, String keyForLog) {
		if (json == null) {
			return null;
		}
		try {
			return mapper.readValue(json, Advertisement.class);
		}
		catch (Exception e) {
			log.warn("Failed to read ad at {}", keyForLog, e);
			return null;
		}
	}

	private String write(Advertisement ad) {
		try {
			return mapper.writeValueAsString(ad);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to serialise ad " + ad.getId(), e);
		}
	}
}

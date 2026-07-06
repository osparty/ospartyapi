package net.osparty.api.web.ws;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link PresenceRegistry}. Each instance writes its live socket count under
 * {@code presence:node:{id}} with a short TTL and registers its id in the {@code presence:nodes} set; the
 * global total is the sum of all live count keys. A node that dies stops refreshing its key, so it TTLs
 * out and drops from the total within {@link #TTL}; its id is pruned from the index lazily on the next
 * read (the same set-index-plus-lazy-prune pattern {@code RedisPartyRepository} uses to avoid {@code KEYS}).
 */
@Component
@Profile("!test")
public class RedisPresenceRegistry implements PresenceRegistry {
	private static final Logger log = LoggerFactory.getLogger(RedisPresenceRegistry.class);

	private static final String NODES_KEY = "presence:nodes";
	private static final String NODE_PREFIX = "presence:node:";
	// A few seconds' slack over the 5s presence tick, so a briefly-slow node isn't dropped and re-added.
	private static final Duration TTL = Duration.ofSeconds(15);

	private final StringRedisTemplate redis;
	/** Unique per process; a restart just leaves a stale key that TTLs out in seconds. */
	private final String nodeId = UUID.randomUUID().toString();

	public RedisPresenceRegistry(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public int record(int localCount) {
		try {
			redis.opsForValue().set(NODE_PREFIX + nodeId, Integer.toString(localCount), TTL);
			redis.opsForSet().add(NODES_KEY, nodeId);

			Set<String> ids = redis.opsForSet().members(NODES_KEY);
			if (ids == null || ids.isEmpty()) {
				return localCount;
			}
			List<String> keys = new ArrayList<>(ids.size());
			List<String> idList = new ArrayList<>(ids);
			for (String id : idList) {
				keys.add(NODE_PREFIX + id);
			}
			List<String> values = redis.opsForValue().multiGet(keys);
			int sum = 0;
			List<String> stale = new ArrayList<>();
			for (int i = 0; i < idList.size(); i++) {
				String value = values == null ? null : values.get(i);
				if (value == null) {
					stale.add(idList.get(i)); // key TTL'd out -> prune its id from the index
					continue;
				}
				try {
					sum += Integer.parseInt(value);
				}
				catch (NumberFormatException ignored) {
					stale.add(idList.get(i));
				}
			}
			if (!stale.isEmpty()) {
				redis.opsForSet().remove(NODES_KEY, stale.toArray());
			}
			return sum;
		}
		catch (Exception e) {
			// Redis blip: fall back to the local count so presence still updates for this node's clients.
			log.debug("presence aggregation failed, using local count: {}", e.toString());
			return localCount;
		}
	}
}

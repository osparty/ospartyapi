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

@Component
@Profile("!test")
public class RedisPresenceRegistry implements PresenceRegistry {
	private static final Logger log = LoggerFactory.getLogger(RedisPresenceRegistry.class);

	private static final String NODES_KEY = "presence:nodes";
	private static final String NODE_PREFIX = "presence:node:";
	private static final Duration TTL = Duration.ofSeconds(15);

	private final StringRedisTemplate redis;
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
					stale.add(idList.get(i));
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
			log.debug("presence aggregation failed, using local count: {}", e.toString());
			return localCount;
		}
	}
}

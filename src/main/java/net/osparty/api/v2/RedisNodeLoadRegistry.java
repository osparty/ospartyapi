package net.osparty.api.v2;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed node load: each node writes its member count to {@code pv2:load:{nodeId}} on the heartbeat
 * and reads its peers' back in the same pass.
 *
 * <p>The key's TTL matches the ownership lock's, so a node that stops heartbeating drops out of placement in
 * the same window it loses its rooms — nothing is ever routed to a node that is already being failed over.
 *
 * <p>Peer loads are cached between heartbeats rather than read per host: placement runs on the {@code host}
 * frame, and a 10 s-stale member count is more than accurate enough to decide which of two nodes is busier.
 * It also keeps a burst of party creations from turning into a burst of Redis scans.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class RedisNodeLoadRegistry implements NodeLoadRegistry {
	private static final Logger log = LoggerFactory.getLogger(RedisNodeLoadRegistry.class);

	private static final String LOAD_PREFIX = "pv2:load:";

	/** Matches the ownership lock TTL: a node stops being a placement candidate as it stops being an owner. */
	private static final Duration TTL = Duration.ofSeconds(30);

	private static final int SCAN_BATCH = 64;

	private final StringRedisTemplate redis;
	private final String nodeId;

	/**
	 * How many members ahead of the lightest node this one must be before a new party is worth sending
	 * elsewhere. A redirect costs the host a reconnect, so small differences are not worth correcting —
	 * and parties end, which levels the two out on its own.
	 */
	private final int threshold;

	/** Last read of every live node's load, refreshed on {@link #publish}. Empty until the first heartbeat. */
	private volatile Map<String, Integer> peers = Map.of();

	public RedisNodeLoadRegistry(StringRedisTemplate redis, NodeIdentity node,
		@Value("${app.party-v2.rebalance-threshold:20}") int threshold) {
		this.redis = redis;
		this.nodeId = node.nodeId();
		this.threshold = threshold;
	}

	@Override
	public void publish(int members) {
		try {
			redis.opsForValue().set(LOAD_PREFIX + nodeId, Integer.toString(members), TTL);
		}
		catch (Exception e) {
			log.debug("Party V2 load publish failed: {}", e.toString());
		}
		peers = readPeers();
	}

	@Override
	public void retire() {
		try {
			redis.delete(LOAD_PREFIX + nodeId);
		}
		catch (Exception e) {
			log.debug("Party V2 load retire failed: {}", e.toString());
		}
		peers = Map.of();
	}

	@Override
	public Optional<String> preferredHost(int selfMembers) {
		Map<String, Integer> snapshot = peers;
		if (snapshot.isEmpty()) {
			// Single node, or we have not completed a heartbeat yet: host here.
			return Optional.empty();
		}
		int lightest = Integer.MAX_VALUE;
		for (int load : snapshot.values()) {
			lightest = Math.min(lightest, load);
		}
		if (selfMembers - lightest < threshold) {
			return Optional.empty();
		}
		// Spread across everything within half a threshold of the lightest node rather than piling onto the
		// single minimum: parties are created concurrently, and they all read the same snapshot.
		int cutoff = lightest + threshold / 2;
		List<String> candidates = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
			if (entry.getValue() <= cutoff) {
				candidates.add(entry.getKey());
			}
		}
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
	}

	/** Every live node's load except this one's — self is never a redirect target. */
	private Map<String, Integer> readPeers() {
		Map<String, Integer> loads = new LinkedHashMap<>();
		try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
			.match(LOAD_PREFIX + "*").count(SCAN_BATCH).build())) {
			while (cursor.hasNext()) {
				String key = cursor.next();
				String peer = key.substring(LOAD_PREFIX.length());
				if (peer.equals(nodeId)) {
					continue;
				}
				String value = redis.opsForValue().get(key);
				if (value == null) {
					// Expired between the scan and the read: the node is gone, which is the same answer.
					continue;
				}
				try {
					loads.put(peer, Integer.parseInt(value.trim()));
				}
				catch (NumberFormatException ignored) {
					// A malformed value is not worth failing placement over.
				}
			}
		}
		catch (Exception e) {
			// Keep the previous view rather than emptying it: a Redis blip should not collapse placement
			// onto this node for the next ten seconds.
			log.debug("Party V2 load scan failed: {}", e.toString());
			return peers;
		}
		return loads;
	}
}

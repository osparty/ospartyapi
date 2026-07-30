package net.osparty.api.party;

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

	/**
	 * Members this node has sent to each peer since the last refresh, counted optimistically so placement
	 * stops sending everyone to the same node.
	 *
	 * <p>The peer view is only as fresh as the heartbeat, ten seconds. At any real party-creation rate that
	 * is hundreds of decisions taken against one frozen snapshot: every host in the window sees the same
	 * lightest node, all of them are sent there, and the next refresh sends them all back. The result is a
	 * herd oscillating between nodes rather than a balance — measured at 83% of parties being redirected,
	 * with the load still ending up 40/60.
	 *
	 * <p>Counting our own placements against the peer's load closes the gap between refreshes: after enough
	 * of them the peer stops looking lighter, and the herd disperses without another Redis round trip.
	 */
	private final Map<String, Integer> placedSincePublish = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Members to assume each placement brings. A redirected host arrives with its party behind it, so
	 * counting one member per placement would under-shoot by the whole party; this is deliberately a guess,
	 * since the point is only to damp the herd, not to predict the roster.
	 */
	private static final int ASSUMED_PARTY_SIZE = 5;

	public RedisNodeLoadRegistry(StringRedisTemplate redis, NodeIdentity node,
		@Value("${app.party.rebalance-threshold:20}") int threshold) {
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
			log.debug("Party load publish failed: {}", e.toString());
		}
		// Real numbers have arrived; the optimistic tally has served its purpose.
		placedSincePublish.clear();
		peers = readPeers();
	}

	@Override
	public void retire() {
		try {
			redis.delete(LOAD_PREFIX + nodeId);
		}
		catch (Exception e) {
			log.debug("Party load retire failed: {}", e.toString());
		}
		peers = Map.of();
		placedSincePublish.clear();
	}

	@Override
	public Optional<String> preferredHost(int selfMembers) {
		Map<String, Integer> snapshot = peers;
		if (snapshot.isEmpty()) {
			// Single node, or we have not completed a heartbeat yet: host here.
			return Optional.empty();
		}
		// Effective load: what Redis last told us, plus what we have sent there since.
		Map<String, Integer> effective = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
			effective.put(entry.getKey(), entry.getValue()
				+ placedSincePublish.getOrDefault(entry.getKey(), 0) * ASSUMED_PARTY_SIZE);
		}
		int lightest = Integer.MAX_VALUE;
		for (int load : effective.values()) {
			lightest = Math.min(lightest, load);
		}
		if (selfMembers - lightest < threshold) {
			return Optional.empty();
		}
		// Spread across everything within half a threshold of the lightest node rather than piling onto the
		// single minimum: parties are created concurrently, and they all read the same snapshot.
		int cutoff = lightest + threshold / 2;
		List<String> candidates = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : effective.entrySet()) {
			if (entry.getValue() <= cutoff) {
				candidates.add(entry.getKey());
			}
		}
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		String chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
		placedSincePublish.merge(chosen, 1, Integer::sum);
		return Optional.of(chosen);
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
			log.debug("Party load scan failed: {}", e.toString());
			return peers;
		}
		return loads;
	}
}

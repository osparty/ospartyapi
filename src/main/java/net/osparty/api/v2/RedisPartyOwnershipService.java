package net.osparty.api.v2;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed Party V2 ownership (PARTY_V2_MIGRATION.md §10). Ownership is a {@code SET NX EX} lock at
 * {@code pv2:owner:{room}} holding this node's id; the winner also writes the {@code pv2:party:{room}} hash.
 * Renew/release are guarded by a compare-and-act Lua script so a node only ever touches a lock it still
 * holds — that is the fence against split-brain (§16 R5). The lock's TTL is the failover trigger: when an
 * owner dies its lock expires and the room becomes claimable again.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class RedisPartyOwnershipService implements PartyOwnershipService {
	private static final Logger log = LoggerFactory.getLogger(RedisPartyOwnershipService.class);

	private static final String OWNER_PREFIX = "pv2:owner:";
	private static final String PARTY_PREFIX = "pv2:party:";
	private static final Duration TTL = Duration.ofSeconds(30);
	private static final long TTL_MS = TTL.toMillis();

	/**
	 * How long the party hash outlives the owner lock. This is the handover window: within it a room with
	 * no owner is "changing hands" rather than "gone", which is what lets a joiner be told to retry.
	 * Applies to both drain paths — a graceful release shortens the hash to exactly this, and a hard kill
	 * leaves it standing this much longer than the lock it outlived.
	 */
	private static final Duration HANDOVER = Duration.ofSeconds(15);
	private static final long HANDOVER_MS = HANDOVER.toMillis();
	private static final long PARTY_TTL_MS = TTL.plus(HANDOVER).toMillis();

	/** Bump the owner-lock + party-hash TTLs only if this node still owns the lock. */
	private static final RedisScript<Long> RENEW = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then "
			+ "redis.call('pexpire', KEYS[1], ARGV[2]); "
			+ "redis.call('pexpire', KEYS[2], ARGV[3]); "
			+ "return 1 else return 0 end",
		Long.class);

	/** Delete the owner-lock + party-hash only if this node still owns the lock. */
	private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then "
			+ "redis.call('del', KEYS[1]); "
			+ "redis.call('del', KEYS[2]); "
			+ "return 1 else return 0 end",
		Long.class);

	/**
	 * Hand the room over: drop our lock but keep the party hash alive for the handover window, so a joiner
	 * arriving before the host re-claims sees a room in transit rather than no room at all.
	 */
	private static final RedisScript<Long> HANDOVER_RELEASE = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then "
			+ "redis.call('del', KEYS[1]); "
			+ "redis.call('pexpire', KEYS[2], ARGV[2]); "
			+ "return 1 else return 0 end",
		Long.class);

	private final StringRedisTemplate redis;
	private final String nodeId;

	public RedisPartyOwnershipService(StringRedisTemplate redis, NodeIdentity node) {
		this.redis = redis;
		this.nodeId = node.nodeId();
	}

	@Override
	public Claim claim(String room) {
		try {
			Boolean won = redis.opsForValue().setIfAbsent(OWNER_PREFIX + room, nodeId, TTL);
			if (Boolean.TRUE.equals(won)) {
				writePartyMeta(room);
				return Claim.CLAIMED;
			}
			String current = redis.opsForValue().get(OWNER_PREFIX + room);
			return nodeId.equals(current) ? Claim.ALREADY_OWNED_BY_SELF : Claim.OWNED_BY_OTHER;
		}
		catch (Exception e) {
			// Redis unreachable: fall back to serving locally rather than dropping the party.
			log.warn("Party V2 claim({}) failed, assuming local ownership: {}", room, e.toString());
			return Claim.CLAIMED;
		}
	}

	@Override
	public Optional<Owner> lookup(String room) {
		try {
			String owner = redis.opsForValue().get(OWNER_PREFIX + room);
			return (owner == null || owner.isBlank()) ? Optional.empty() : Optional.of(new Owner(owner));
		}
		catch (Exception e) {
			log.debug("Party V2 lookup({}) failed: {}", room, e.toString());
			return Optional.empty();
		}
	}

	@Override
	public boolean renew(String room) {
		return exec(RENEW, room, Long.toString(TTL_MS), Long.toString(PARTY_TTL_MS));
	}

	@Override
	public void release(String room) {
		exec(RELEASE, room);
	}

	@Override
	public void releaseForHandover(String room) {
		exec(HANDOVER_RELEASE, room, Long.toString(HANDOVER_MS));
	}

	@Override
	public boolean handoverPending(String room) {
		try {
			// The party hash outlives the owner lock by the handover window, so its presence with no owner
			// is exactly "this room existed a moment ago and is being re-claimed".
			return Boolean.TRUE.equals(redis.hasKey(PARTY_PREFIX + room));
		}
		catch (Exception e) {
			// Redis unreachable: say no rather than park a joiner in a retry loop we cannot resolve.
			log.debug("Party V2 handoverPending({}) failed: {}", room, e.toString());
			return false;
		}
	}

	@Override
	public boolean ownedBySelf(String room) {
		try {
			return nodeId.equals(redis.opsForValue().get(OWNER_PREFIX + room));
		}
		catch (Exception e) {
			// Redis unreachable: keep serving rather than dropping a live party over a transient blip.
			log.debug("Party V2 ownedBySelf({}) failed, assuming still owner: {}", room, e.toString());
			return true;
		}
	}

	/**
	 * Run a compare-and-act ownership script. {@code nodeId} is always {@code ARGV[1]} (the fence); each
	 * script's own arguments follow.
	 *
	 * @return true if the script reported that this node still holds the lock.
	 */
	private boolean exec(RedisScript<Long> script, String room, String... argv) {
		try {
			Object[] args = new Object[argv.length + 1];
			args[0] = nodeId;
			System.arraycopy(argv, 0, args, 1, argv.length);
			Long held = redis.execute(script, List.of(OWNER_PREFIX + room, PARTY_PREFIX + room), args);
			return held != null && held == 1L;
		}
		catch (Exception e) {
			// Treat a Redis blip as "still ours": dropping a live room is worse than a late handover.
			log.debug("Party V2 ownership script failed for {}: {}", room, e.toString());
			return true;
		}
	}

	private void writePartyMeta(String room) {
		try {
			redis.opsForHash().putAll(PARTY_PREFIX + room, java.util.Map.of(
				"owner", nodeId,
				"createdAt", Long.toString(System.currentTimeMillis())));
			// Outlives the lock by the handover window (see HANDOVER): the hash is what marks a room as
			// "in transit" once its owner is gone.
			redis.expire(PARTY_PREFIX + room, Duration.ofMillis(PARTY_TTL_MS));
		}
		catch (Exception e) {
			log.debug("Party V2 party-meta write failed for {}: {}", room, e.toString());
		}
	}
}

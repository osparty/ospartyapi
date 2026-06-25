package net.osparty.api;

import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import java.util.List;
import java.util.Optional;

/**
 * Storage for party ads. Two implementations: {@link InMemoryPartyRepository}
 * (default; used by tests) and {@link RedisPartyRepository} (selected with
 * {@code app.storage=redis}, survives restarts via native key TTL).
 */
public interface PartyRepository
{
	/** All open ads, newest first, optionally filtered to one activity id. */
	List<Party> list(String activity);

	/** Advertise a party. Replaces any existing ad from the same host. */
	Party create(PartyRequest request);

	/** Host keep-alive: refresh the ad's liveness. @return the ad if it exists. */
	Optional<Party> heartbeat(String id);

	Optional<Party> delete(String id);

	/**
	 * Drop ads not heard from within {@code maxAgeMs}. Backends with native key
	 * expiry (Redis) don't need this and return 0.
	 */
	default int evictStale(long maxAgeMs)
	{
		return 0;
	}
}

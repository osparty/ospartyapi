package net.osparty.api;

import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
import java.util.List;
import java.util.Optional;

/**
 * Storage for party ads. The production implementation is {@link RedisPartyRepository}
 * (persists across restarts, native key TTL for liveness); tests use an in-memory fake.
 */
public interface PartyRepository
{
	/**
	 * Open <b>public</b> ads, newest first, optionally filtered to one activity id.
	 * Private parties are excluded — they're reached via {@link #findByInviteCode}.
	 */
	List<Party> list(String activity);

	/** Look up any party (public or private) by its invite code. */
	Optional<Party> findByInviteCode(String code);

	/** Look up the ad currently hosted by {@code host}, if any (for rejoin-on-restart). */
	Optional<Party> findByHost(String host);

	/**
	 * Advertise a party. Replaces any existing ad from the same host. {@code hostKey}
	 * is the caller's secret credential: stored alongside the ad (never returned to
	 * clients) and required on later host-only mutations (see {@link #authorize}). A
	 * null/blank key creates an unauthenticated ad (back-compat for older clients).
	 */
	Party create(PartyRequest request, String hostKey);

	/**
	 * Apply a partial update to an ad (host-only fields). Non-null {@code patch} fields
	 * are written; identity fields (id/host/activity/passphrase/inviteCode) can't change.
	 * Always refreshes the ad's liveness/TTL — an update implies the host is alive, and
	 * an empty patch is a pure liveness touch (the socket-as-heartbeat mechanism).
	 * @return the ad if it exists.
	 */
	Optional<Party> update(String id, PartyUpdate patch);

	/**
	 * Back-compat keepalive behind {@code PUT …/{id}/heartbeat}: a narrow
	 * {@link #update} over the four fields the old heartbeat carried (roles as a
	 * comma-separated list). Liveness now comes from the host's socket; this stays
	 * for older plugin clients still on the REST heartbeat.
	 */
	default Optional<Party> heartbeat(String id, Integer size, String world, String layout, String roles)
	{
		PartyUpdate patch = new PartyUpdate();
		patch.setSize(size);
		patch.setWorld(world);
		patch.setLayout(layout);
		patch.setNeededRoles(PartyFactory.parseRoles(roles));
		return update(id, patch);
	}

	Optional<Party> delete(String id);

	/**
	 * Whether {@code hostKey} may perform a host-only mutation on party {@code id}:
	 * {@link Authorization#NOT_FOUND} when no such ad exists, {@link Authorization#FORBIDDEN}
	 * when the ad has a credential the key doesn't match, else {@link Authorization#OK}
	 * (including ads created without a credential, which stay open for back-compat).
	 */
	Authorization authorize(String id, String hostKey);

	/** Outcome of {@link #authorize}. */
	enum Authorization
	{
		OK, NOT_FOUND, FORBIDDEN
	}
}

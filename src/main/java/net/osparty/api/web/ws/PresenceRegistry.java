package net.osparty.api.web.ws;

/**
 * Aggregates the "active users" count across API instances. Each instance reports its own live socket
 * count; the returned value is the best-known GLOBAL total so every client sees the same number no matter
 * which instance it connected to.
 *
 * <p>Backed by Redis in the deployed setup ({@link RedisPresenceRegistry}); a {@link LocalPresenceRegistry}
 * no-op (returns the local count unchanged) stands in for the test profile so the suite needs no Redis.
 */
public interface PresenceRegistry {
	/**
	 * Publish this instance's local connection count and return the current global total across all live
	 * instances. Never throws — on a backing-store hiccup it degrades to the local count.
	 */
	int record(int localCount);
}

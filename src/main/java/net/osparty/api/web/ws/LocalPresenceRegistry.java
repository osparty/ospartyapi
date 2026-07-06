package net.osparty.api.web.ws;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node / test {@link PresenceRegistry}: the global count IS the local count. Active in the test
 * profile (no Redis) — mirrors {@code FakePartyRepository}. Production uses {@link RedisPresenceRegistry}.
 */
@Component
@Profile("test")
public class LocalPresenceRegistry implements PresenceRegistry {
	@Override
	public int record(int localCount) {
		return localCount;
	}
}

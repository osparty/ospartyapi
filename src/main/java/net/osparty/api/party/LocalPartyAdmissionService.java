package net.osparty.api.party;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.osparty.api.service.AdvertisementFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-memory grants for single-node deployments and tests. {@link RedisPartyAdmissionService} is the
 * multi-node implementation.
 *
 * <p>Entries expire on read rather than on a timer: nothing here needs a sweep, because a grant nobody looks
 * up again costs one map entry in a process that is not the one serving production.
 */
@Component
@Profile("test")
public class LocalPartyAdmissionService implements PartyAdmissionService {
	private final Map<String, Long> grants = new ConcurrentHashMap<>();

	@Override
	public void grant(String room, String name) {
		String key = key(room, name);
		if (key != null) {
			grants.put(key, System.currentTimeMillis() + TTL.toMillis());
		}
	}

	@Override
	public boolean isGranted(String room, String name) {
		String key = key(room, name);
		if (key == null) {
			return false;
		}
		Long expiresAt = grants.get(key);
		if (expiresAt == null) {
			return false;
		}
		if (expiresAt < System.currentTimeMillis()) {
			grants.remove(key);
			return false;
		}
		return true;
	}

	/**
	 * A NUL separator rather than anything printable: room keys are passphrases built from item names and so
	 * contain spaces, which would let {@code ("a b", "c")} and {@code ("a", "b c")} collide into one entry --
	 * a grant for one party admitting someone to another.
	 */
	private static String key(String room, String name) {
		if (room == null || room.isBlank() || name == null || name.isBlank()) {
			return null;
		}
		return room + '\0' + AdvertisementFactory.normalizeHost(name);
	}
}

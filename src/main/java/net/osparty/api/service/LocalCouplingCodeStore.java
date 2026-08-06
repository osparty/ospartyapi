package net.osparty.api.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In-memory coupling codes for tests and single-node runs. {@link RedisCouplingCodeStore} is the real one. */
@Component
@Profile("test")
public class LocalCouplingCodeStore implements CouplingCodeStore {
	private final Map<Long, Entry> pending = new ConcurrentHashMap<>();

	private record Entry(Pending pending, Instant expiresAt) {
		boolean expired() {
			return Instant.now().isAfter(expiresAt);
		}
	}

	@Override
	public boolean putIfAbsent(long accountHash, String code, String challengerSessionId) {
		Entry fresh = new Entry(new Pending(code, challengerSessionId), Instant.now().plus(TTL));
		// merge rather than putIfAbsent so an expired entry is replaced instead of blocking, which is what
		// the TTL does in Redis and what stops an abandoned request becoming a permanent lockout.
		return pending.merge(accountHash, fresh, (existing, incoming) ->
			existing.expired() ? incoming : existing) == fresh;
	}

	@Override
	public Optional<Pending> get(long accountHash) {
		Entry entry = pending.get(accountHash);
		if (entry == null) {
			return Optional.empty();
		}
		if (entry.expired()) {
			pending.remove(accountHash, entry);
			return Optional.empty();
		}
		return Optional.of(entry.pending());
	}

	@Override
	public void remove(long accountHash) {
		pending.remove(accountHash);
	}
}

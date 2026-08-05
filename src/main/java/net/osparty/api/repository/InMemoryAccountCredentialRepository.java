package net.osparty.api.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Credentials with no database under them, for tests and single-node runs without Postgres. */
@Repository
@Profile("test")
public class InMemoryAccountCredentialRepository implements AccountCredentialRepository {
	private final Map<String, Credential> byTokenHash = new ConcurrentHashMap<>();
	private final List<Enrolment> enrolments = new ArrayList<>();

	/** One recorded enrolment. Exposed so a test can assert the trail exists, which is its whole point. */
	public record Enrolment(long accountHash, String clientIp, boolean firstDevice, Instant at) {
	}

	@Override
	public void insert(String tokenHash, long accountHash, String label) {
		Instant now = Instant.now();
		byTokenHash.put(tokenHash, new Credential(tokenHash, accountHash, label, now, now, null));
	}

	@Override
	public Optional<Credential> findByTokenHash(String tokenHash) {
		return Optional.ofNullable(byTokenHash.get(tokenHash));
	}

	@Override
	public List<Credential> findActiveByAccountHash(long accountHash) {
		return byTokenHash.values().stream()
			.filter(c -> c.accountHash() == accountHash && c.active())
			.sorted(Comparator.comparing(Credential::issuedAt))
			.toList();
	}

	@Override
	public int countActiveByAccountHash(long accountHash) {
		return findActiveByAccountHash(accountHash).size();
	}

	@Override
	public void touch(String tokenHash) {
		byTokenHash.computeIfPresent(tokenHash, (key, c) -> new Credential(
			c.tokenHash(), c.accountHash(), c.label(), c.issuedAt(), Instant.now(), c.revokedAt()));
	}

	@Override
	public void revoke(String tokenHash) {
		byTokenHash.computeIfPresent(tokenHash, (key, c) -> c.active()
			? new Credential(c.tokenHash(), c.accountHash(), c.label(), c.issuedAt(), c.lastSeenAt(),
				Instant.now())
			: c);
	}

	@Override
	public int revokeAllForAccount(long accountHash) {
		List<Credential> active = findActiveByAccountHash(accountHash);
		active.forEach(c -> revoke(c.tokenHash()));
		return active.size();
	}

	@Override
	public void logEnrolment(long accountHash, String clientIp, boolean firstDevice) {
		synchronized (enrolments) {
			enrolments.add(new Enrolment(accountHash, clientIp, firstDevice, Instant.now()));
		}
	}

	public List<Enrolment> enrolments() {
		synchronized (enrolments) {
			return List.copyOf(enrolments);
		}
	}
}

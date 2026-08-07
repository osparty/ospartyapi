package net.osparty.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Recovery codes with no database under them, for tests and single-node runs without Postgres. */
@Repository
@Profile("test")
public class InMemoryAccountRecoveryRepository implements AccountRecoveryRepository {
	private final Map<String, RecoveryCode> byCodeHash = new ConcurrentHashMap<>();

	@Override
	public void replaceUnused(long accountHash, List<String> codeHashes) {
		byCodeHash.values().removeIf(c -> c.accountHash() == accountHash && c.unused());
		Instant now = Instant.now();
		for (String codeHash : codeHashes) {
			byCodeHash.put(codeHash, new RecoveryCode(codeHash, accountHash, now, null, null));
		}
	}

	@Override
	public Optional<RecoveryCode> findByCodeHash(String codeHash) {
		return Optional.ofNullable(byCodeHash.get(codeHash));
	}

	/**
	 * {@code computeIfPresent} rather than get-then-put so two threads redeeming the same code cannot both
	 * see it unused -- the same race the SQL version closes with a conditional update.
	 */
	@Override
	public boolean markUsed(String codeHash, String clientIp) {
		boolean[] spent = {false};
		byCodeHash.computeIfPresent(codeHash, (key, c) -> {
			if (!c.unused()) {
				return c;
			}
			spent[0] = true;
			return new RecoveryCode(c.codeHash(), c.accountHash(), c.issuedAt(), Instant.now(), clientIp);
		});
		return spent[0];
	}

	@Override
	public int countUnused(long accountHash) {
		return (int) byCodeHash.values().stream()
			.filter(c -> c.accountHash() == accountHash && c.unused())
			.count();
	}
}

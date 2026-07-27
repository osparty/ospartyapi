package net.osparty.api.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.osparty.api.model.AdBan;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Test-profile stand-in for {@link JdbcBanRepository}, mirroring {@link FakePartyRepository}. Lets
 * the WebSocket tests drive real ban state without a database, and enforces the same
 * one-active-ban-per-subject rule the partial unique indexes enforce in Postgres.
 */
@Repository
@Profile("test")
public class InMemoryBanRepository implements BanRepository {
	private final Map<Long, AdBan> bans = new ConcurrentHashMap<>();
	private final AtomicLong idSequence = new AtomicLong(1);

	@Override
	public synchronized List<ActiveBan> findActive() {
		List<ActiveBan> out = new ArrayList<>();
		for (AdBan ban : bans.values()) {
			if (ban.isActive()) {
				out.add(new ActiveBan(ban.getId(), ban.getHostName(), ban.getAccountHash()));
			}
		}
		return out;
	}

	@Override
	public Optional<AdBan> findById(long id) {
		return Optional.ofNullable(bans.get(id));
	}

	@Override
	public synchronized AdBan ban(String hostName, String hostNameRaw, Long accountHash, String reason,
		String moderatorDiscordId, String moderatorDiscordName, Long sourceReportId) {
		String name = hostName == null ? "" : hostName;
		Optional<AdBan> existing = findActiveBySubject(name, accountHash);
		if (existing.isPresent()) {
			return existing.get();
		}
		AdBan ban = new AdBan();
		ban.setId(idSequence.getAndIncrement());
		ban.setHostName(name);
		ban.setHostNameRaw(hostNameRaw);
		ban.setAccountHash(accountHash);
		ban.setReason(reason);
		ban.setCreatedAt(Instant.now());
		ban.setCreatedByDiscordId(moderatorDiscordId);
		ban.setCreatedByDiscordName(moderatorDiscordName);
		ban.setSourceReportId(sourceReportId);
		bans.put(ban.getId(), ban);
		return ban;
	}

	@Override
	public synchronized List<AdBan> revoke(String hostName, Long accountHash, String moderatorDiscordId,
		String moderatorDiscordName, String reason) {
		String name = hostName == null ? "" : hostName;
		List<AdBan> revoked = new ArrayList<>();
		for (AdBan ban : bans.values()) {
			if (ban.isActive() && matches(ban, name, accountHash)) {
				ban.setRevokedAt(Instant.now());
				ban.setRevokedByDiscordId(moderatorDiscordId);
				ban.setRevokedByDiscordName(moderatorDiscordName);
				ban.setRevokeReason(reason);
				revoked.add(ban);
			}
		}
		return revoked;
	}

	/** Test hook: forget every ban between cases. */
	public synchronized void clear() {
		bans.clear();
	}

	private Optional<AdBan> findActiveBySubject(String hostName, Long accountHash) {
		return bans.values().stream()
			.filter(AdBan::isActive)
			.filter(ban -> matches(ban, hostName, accountHash))
			.min(java.util.Comparator.comparingLong(AdBan::getId));
	}

	private static boolean matches(AdBan ban, String hostName, Long accountHash) {
		boolean byName = !hostName.isEmpty() && hostName.equals(ban.getHostName());
		boolean byHash = accountHash != null && accountHash.equals(ban.getAccountHash());
		return byName || byHash;
	}
}

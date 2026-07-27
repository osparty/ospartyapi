package net.osparty.api.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Test-profile stand-in for {@link JdbcDiscordLinkRepository}. */
@Repository
@Profile("test")
public class InMemoryDiscordLinkRepository implements DiscordLinkRepository {
	private final Map<Long, Link> links = new ConcurrentHashMap<>();
	private final Set<Long> hidden = ConcurrentHashMap.newKeySet();
	private final Set<String> migrations = ConcurrentHashMap.newKeySet();

	@Override
	public void link(long accountHash, String discordId, String discordName) {
		links.put(accountHash, new Link(accountHash, discordId, discordName));
	}

	@Override
	public void unlink(long accountHash) {
		links.remove(accountHash);
	}

	@Override
	public Optional<Link> findByAccountHash(long accountHash) {
		return Optional.ofNullable(links.get(accountHash));
	}

	@Override
	public List<Long> accountHashesFor(String discordId) {
		List<Long> out = new ArrayList<>();
		links.values().stream()
			.filter(link -> link.discordId().equals(discordId))
			.forEach(link -> out.add(link.accountHash()));
		return out;
	}

	@Override
	public List<Link> findAll() {
		return List.copyOf(links.values());
	}

	@Override
	public void setBadgesHidden(long accountHash, boolean isHidden) {
		if (isHidden) {
			hidden.add(accountHash);
		}
		else {
			hidden.remove(accountHash);
		}
	}

	@Override
	public boolean isBadgesHidden(long accountHash) {
		return hidden.contains(accountHash);
	}

	@Override
	public List<Long> findBadgesHidden() {
		return List.copyOf(hidden);
	}

	@Override
	public boolean importIfAbsent(long accountHash, String discordId, String discordName) {
		return links.putIfAbsent(accountHash, new Link(accountHash, discordId, discordName)) == null;
	}

	@Override
	public boolean importHiddenIfAbsent(long accountHash, boolean isHidden) {
		return isHidden && hidden.add(accountHash);
	}

	@Override
	public boolean migrationCompleted(String name) {
		return migrations.contains(name);
	}

	@Override
	public void markMigrationCompleted(String name, String note) {
		migrations.add(name);
	}

	@Override
	public long countLinks() {
		return links.size();
	}

	/** Test hook: forget everything between cases. */
	public void clear() {
		links.clear();
		hidden.clear();
		migrations.clear();
	}
}

package net.osparty.api.repository;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcDiscordLinkRepository implements DiscordLinkRepository {
	private static final String COLUMNS = "account_hash, discord_id, discord_name, verified";

	private static final RowMapper<Link> MAPPER = (ResultSet rs, int row) -> new Link(
		rs.getLong("account_hash"), rs.getString("discord_id"), rs.getString("discord_name"),
		rs.getBoolean("verified"));

	private final JdbcClient db;

	public JdbcDiscordLinkRepository(JdbcClient db) {
		this.db = db;
	}

	/**
	 * {@code verified} is overwritten on conflict like every other column, so relinking from a signed-in
	 * session is how a row written before verification existed becomes trustworthy. It moves both ways on
	 * purpose: a link made without proof should not inherit the standing of the one it replaces.
	 */
	@Override
	public void link(long accountHash, String discordId, String discordName, boolean verified) {
		db.sql("""
				INSERT INTO discord_link (account_hash, discord_id, discord_name, verified)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (account_hash) DO UPDATE
				  SET discord_id = EXCLUDED.discord_id,
				      discord_name = EXCLUDED.discord_name,
				      verified = EXCLUDED.verified,
				      updated_at = now()
				""")
			.params(accountHash, discordId, discordName, verified)
			.update();
	}

	@Override
	public void unlink(long accountHash) {
		db.sql("DELETE FROM discord_link WHERE account_hash = ?").param(accountHash).update();
	}

	@Override
	public Optional<Link> findByAccountHash(long accountHash) {
		return db.sql("SELECT " + COLUMNS + " FROM discord_link WHERE account_hash = ?")
			.param(accountHash)
			.query(MAPPER)
			.optional();
	}

	@Override
	public List<Long> accountHashesFor(String discordId) {
		return db.sql("SELECT account_hash FROM discord_link WHERE discord_id = ?")
			.param(discordId)
			.query(Long.class)
			.list();
	}

	@Override
	public List<Link> findAll() {
		return db.sql("SELECT " + COLUMNS + " FROM discord_link")
			.query(MAPPER)
			.list();
	}

	@Override
	public void setBadgesHidden(long accountHash, boolean hidden) {
		db.sql("""
				INSERT INTO account_preference (account_hash, badges_hidden)
				VALUES (?, ?)
				ON CONFLICT (account_hash) DO UPDATE
				  SET badges_hidden = EXCLUDED.badges_hidden, updated_at = now()
				""")
			.params(accountHash, hidden)
			.update();
	}

	@Override
	public boolean isBadgesHidden(long accountHash) {
		return db.sql("SELECT badges_hidden FROM account_preference WHERE account_hash = ?")
			.param(accountHash)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

	@Override
	public List<Long> findBadgesHidden() {
		return db.sql("SELECT account_hash FROM account_preference WHERE badges_hidden")
			.query(Long.class)
			.list();
	}

	@Override
	public boolean importIfAbsent(long accountHash, String discordId, String discordName) {
		return db.sql("""
				INSERT INTO discord_link (account_hash, discord_id, discord_name)
				VALUES (?, ?, ?)
				ON CONFLICT (account_hash) DO NOTHING
				""")
			.params(accountHash, discordId, discordName)
			.update() > 0;
	}

	@Override
	public boolean importHiddenIfAbsent(long accountHash, boolean hidden) {
		return db.sql("""
				INSERT INTO account_preference (account_hash, badges_hidden)
				VALUES (?, ?)
				ON CONFLICT (account_hash) DO NOTHING
				""")
			.params(accountHash, hidden)
			.update() > 0;
	}

	@Override
	public boolean migrationCompleted(String name) {
		return db.sql("SELECT 1 FROM data_migration WHERE name = ?")
			.param(name)
			.query(Integer.class)
			.optional()
			.isPresent();
	}

	@Override
	public void markMigrationCompleted(String name, String note) {
		db.sql("INSERT INTO data_migration (name, note) VALUES (?, ?) ON CONFLICT (name) DO NOTHING")
			.params(name, note)
			.update();
	}

	@Override
	public long countLinks() {
		Long count = db.sql("SELECT count(*) FROM discord_link").query(Long.class).single();
		return count == null ? 0 : count;
	}
}

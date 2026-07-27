package net.osparty.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import net.osparty.api.model.AdBan;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcBanRepository implements BanRepository {
	private static final String COLUMNS = """
		id, host_name, host_name_raw, account_hash, reason, created_at,
		created_by_discord_id, created_by_discord_name, source_report_id,
		revoked_at, revoked_by_discord_id, revoked_by_discord_name, revoke_reason
		""";

	private static final RowMapper<AdBan> MAPPER = JdbcBanRepository::mapRow;

	private final JdbcClient db;

	public JdbcBanRepository(JdbcClient db) {
		this.db = db;
	}

	@Override
	public List<ActiveBan> findActive() {
		return db.sql("SELECT id, host_name, account_hash FROM ad_ban WHERE revoked_at IS NULL")
			.query((ResultSet rs, int row) -> new ActiveBan(
				rs.getLong("id"),
				rs.getString("host_name"),
				nullableLong(rs, "account_hash")))
			.list();
	}

	@Override
	public Optional<AdBan> findById(long id) {
		return db.sql("SELECT " + COLUMNS + " FROM ad_ban WHERE id = ?")
			.param(id)
			.query(MAPPER)
			.optional();
	}

	@Override
	public AdBan ban(String hostName, String hostNameRaw, Long accountHash, String reason,
		String moderatorDiscordId, String moderatorDiscordName, Long sourceReportId) {
		String name = hostName == null ? "" : hostName;
		// Idempotent by design: a moderator can double-click, and two moderators can act on the
		// same report at once. The read below catches the common case; the partial unique indexes
		// catch the concurrent one, and we resolve that by reading back the winner.
		Optional<AdBan> existing = findActiveBySubject(name, accountHash);
		if (existing.isPresent()) {
			return existing.get();
		}
		try {
			return db.sql("""
					INSERT INTO ad_ban (host_name, host_name_raw, account_hash, reason,
						created_by_discord_id, created_by_discord_name, source_report_id)
					VALUES (?, ?, ?, ?, ?, ?, ?)
					RETURNING
					""" + COLUMNS)
				.params(name, hostNameRaw, accountHash, reason,
					moderatorDiscordId, moderatorDiscordName, sourceReportId)
				.query(MAPPER)
				.single();
		}
		catch (DuplicateKeyException e) {
			return findActiveBySubject(name, accountHash)
				.orElseThrow(() -> new IllegalStateException(
					"Ban conflicted with an active ban that then disappeared: " + name, e));
		}
	}

	@Override
	public List<AdBan> revoke(String hostName, Long accountHash, String moderatorDiscordId,
		String moderatorDiscordName, String reason) {
		String name = hostName == null ? "" : hostName;
		return db.sql("""
				UPDATE ad_ban
				   SET revoked_at = now(),
				       revoked_by_discord_id = ?,
				       revoked_by_discord_name = ?,
				       revoke_reason = ?
				 WHERE revoked_at IS NULL
				   AND ((? <> '' AND host_name = ?) OR (? IS NOT NULL AND account_hash = ?))
				RETURNING
				""" + COLUMNS)
			.params(moderatorDiscordId, moderatorDiscordName, reason,
				name, name, accountHash, accountHash)
			.query(MAPPER)
			.list();
	}

	/** Matches on either identifier: a name ban and a hash ban are both bans on the same person. */
	private Optional<AdBan> findActiveBySubject(String hostName, Long accountHash) {
		return db.sql("SELECT " + COLUMNS + """
				  FROM ad_ban
				 WHERE revoked_at IS NULL
				   AND ((? <> '' AND host_name = ?) OR (? IS NOT NULL AND account_hash = ?))
				 ORDER BY id
				 LIMIT 1
				""")
			.params(hostName, hostName, accountHash, accountHash)
			.query(MAPPER)
			.optional();
	}

	private static AdBan mapRow(ResultSet rs, int rowNum) throws SQLException {
		AdBan ban = new AdBan();
		ban.setId(rs.getLong("id"));
		ban.setHostName(rs.getString("host_name"));
		ban.setHostNameRaw(rs.getString("host_name_raw"));
		ban.setAccountHash(nullableLong(rs, "account_hash"));
		ban.setReason(rs.getString("reason"));
		ban.setCreatedAt(instant(rs, "created_at"));
		ban.setCreatedByDiscordId(rs.getString("created_by_discord_id"));
		ban.setCreatedByDiscordName(rs.getString("created_by_discord_name"));
		ban.setSourceReportId(nullableLong(rs, "source_report_id"));
		ban.setRevokedAt(instant(rs, "revoked_at"));
		ban.setRevokedByDiscordId(rs.getString("revoked_by_discord_id"));
		ban.setRevokedByDiscordName(rs.getString("revoked_by_discord_name"));
		ban.setRevokeReason(rs.getString("revoke_reason"));
		return ban;
	}

	static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}
}

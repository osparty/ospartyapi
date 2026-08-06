package net.osparty.api.repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcAccountCredentialRepository implements AccountCredentialRepository {
	private static final String COLUMNS =
		"token_hash, account_hash, label, issued_at, last_seen_at, revoked_at";

	private static final RowMapper<Credential> MAPPER = (ResultSet rs, int row) -> new Credential(
		rs.getString("token_hash"),
		rs.getLong("account_hash"),
		rs.getString("label"),
		instant(rs.getTimestamp("issued_at")),
		instant(rs.getTimestamp("last_seen_at")),
		instant(rs.getTimestamp("revoked_at")));

	private final JdbcClient db;

	public JdbcAccountCredentialRepository(JdbcClient db) {
		this.db = db;
	}

	@Override
	public void insert(String tokenHash, long accountHash, String label) {
		db.sql("INSERT INTO account_credential (token_hash, account_hash, label) VALUES (?, ?, ?)")
			.params(tokenHash, accountHash, label)
			.update();
	}

	@Override
	public void updateLabel(String tokenHash, String label) {
		db.sql("UPDATE account_credential SET label = ? WHERE token_hash = ?")
			.params(label, tokenHash)
			.update();
	}

	@Override
	public Optional<Credential> findByTokenHash(String tokenHash) {
		return db.sql("SELECT " + COLUMNS + " FROM account_credential WHERE token_hash = ?")
			.param(tokenHash)
			.query(MAPPER)
			.optional();
	}

	@Override
	public List<Credential> findActiveByAccountHash(long accountHash) {
		return db.sql("SELECT " + COLUMNS + " FROM account_credential"
				+ " WHERE account_hash = ? AND revoked_at IS NULL ORDER BY issued_at")
			.param(accountHash)
			.query(MAPPER)
			.list();
	}

	@Override
	public int countActiveByAccountHash(long accountHash) {
		Integer count = db.sql("SELECT count(*) FROM account_credential"
				+ " WHERE account_hash = ? AND revoked_at IS NULL")
			.param(accountHash)
			.query(Integer.class)
			.single();
		return count == null ? 0 : count;
	}

	@Override
	public void touch(String tokenHash) {
		db.sql("UPDATE account_credential SET last_seen_at = now() WHERE token_hash = ?")
			.param(tokenHash)
			.update();
	}

	@Override
	public void revoke(String tokenHash) {
		db.sql("UPDATE account_credential SET revoked_at = now()"
				+ " WHERE token_hash = ? AND revoked_at IS NULL")
			.param(tokenHash)
			.update();
	}

	@Override
	public int revokeAllForAccount(long accountHash) {
		return db.sql("UPDATE account_credential SET revoked_at = now()"
				+ " WHERE account_hash = ? AND revoked_at IS NULL")
			.param(accountHash)
			.update();
	}

	@Override
	public void logEnrolment(long accountHash, String clientIp, boolean firstDevice) {
		db.sql("INSERT INTO account_enrolment (account_hash, client_ip, first_device) VALUES (?, ?, ?)")
			.params(accountHash, clientIp, firstDevice)
			.update();
	}

	private static java.time.Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}

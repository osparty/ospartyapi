package net.osparty.api.repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class JdbcAccountRecoveryRepository implements AccountRecoveryRepository {
	private static final String COLUMNS = "code_hash, account_hash, issued_at, used_at, used_ip";

	private static final RowMapper<RecoveryCode> MAPPER = (ResultSet rs, int row) -> new RecoveryCode(
		rs.getString("code_hash"),
		rs.getLong("account_hash"),
		instant(rs.getTimestamp("issued_at")),
		instant(rs.getTimestamp("used_at")),
		rs.getString("used_ip"));

	private final JdbcClient db;

	public JdbcAccountRecoveryRepository(JdbcClient db) {
		this.db = db;
	}

	/**
	 * Transactional because the delete and the insert are one act from the user's point of view: a crash
	 * between them would leave an account with no way back in and no sheet of codes to show for it.
	 */
	@Override
	@Transactional
	public void replaceUnused(long accountHash, List<String> codeHashes) {
		db.sql("DELETE FROM account_recovery_code WHERE account_hash = ? AND used_at IS NULL")
			.param(accountHash)
			.update();
		for (String codeHash : codeHashes) {
			db.sql("INSERT INTO account_recovery_code (code_hash, account_hash) VALUES (?, ?)")
				.params(codeHash, accountHash)
				.update();
		}
	}

	@Override
	public Optional<RecoveryCode> findByCodeHash(String codeHash) {
		return db.sql("SELECT " + COLUMNS + " FROM account_recovery_code WHERE code_hash = ?")
			.param(codeHash)
			.query(MAPPER)
			.optional();
	}

	@Override
	public boolean markUsed(String codeHash, String clientIp) {
		return db.sql("UPDATE account_recovery_code SET used_at = now(), used_ip = ?"
				+ " WHERE code_hash = ? AND used_at IS NULL")
			.params(clientIp, codeHash)
			.update() == 1;
	}

	@Override
	public int countUnused(long accountHash) {
		Integer count = db.sql("SELECT count(*) FROM account_recovery_code"
				+ " WHERE account_hash = ? AND used_at IS NULL")
			.param(accountHash)
			.query(Integer.class)
			.single();
		return count == null ? 0 : count;
	}

	private static java.time.Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}

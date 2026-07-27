package net.osparty.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import net.osparty.api.model.AdReport;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcAdReportRepository implements AdReportRepository {
	private static final String COLUMNS = """
		id, created_at, party_id, host_name, host_name_raw, host_account_hash, activity,
		description, world, capacity, party_size, invite_code, ad_snapshot::text AS ad_snapshot,
		reporter_name, reporter_account_hash, reporter_session_id, reporter_ip_hash,
		status, reviewed_at, reviewed_by_discord_id, reviewed_by_discord_name, notified,
		discord_channel_id, discord_message_id, resulting_ban_id
		""";

	private static final RowMapper<AdReport> MAPPER = JdbcAdReportRepository::mapRow;

	private final JdbcClient db;

	public JdbcAdReportRepository(JdbcClient db) {
		this.db = db;
	}

	@Override
	public long insert(AdReport report) {
		return db.sql("""
				INSERT INTO ad_report (party_id, host_name, host_name_raw, host_account_hash,
					activity, description, world, capacity, party_size, invite_code, ad_snapshot,
					reporter_name, reporter_account_hash, reporter_session_id, reporter_ip_hash,
					status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
				RETURNING id
				""")
			.params(report.getPartyId(), report.getHostName(), report.getHostNameRaw(),
				report.getHostAccountHash(), report.getActivity(), report.getDescription(),
				report.getWorld(), report.getCapacity(), report.getPartySize(),
				report.getInviteCode(), report.getAdSnapshot(),
				report.getReporterName(), report.getReporterAccountHash(),
				report.getReporterSessionId(), report.getReporterIpHash(),
				report.getStatus())
			.query(Long.class)
			.single();
	}

	@Override
	public Optional<AdReport> findById(long id) {
		return db.sql("SELECT " + COLUMNS + " FROM ad_report WHERE id = ?")
			.param(id)
			.query(MAPPER)
			.optional();
	}

	@Override
	public void markNotified(long id, String channelId, String messageId) {
		db.sql("""
				UPDATE ad_report
				   SET notified = TRUE, discord_channel_id = ?, discord_message_id = ?
				 WHERE id = ?
				""")
			.params(channelId, messageId, id)
			.update();
	}

	@Override
	public boolean markReviewed(long id, String status, String moderatorDiscordId,
		String moderatorDiscordName, Long banId) {
		return db.sql("""
				UPDATE ad_report
				   SET status = ?, reviewed_at = now(), reviewed_by_discord_id = ?,
				       reviewed_by_discord_name = ?, resulting_ban_id = ?
				 WHERE id = ?
				""")
			.params(status, moderatorDiscordId, moderatorDiscordName, banId, id)
			.update() > 0;
	}

	@Override
	public int purgePending(Duration olderThan) {
		return db.sql("""
				DELETE FROM ad_report
				 WHERE status = 'PENDING'
				   AND created_at < now() - make_interval(secs => ?)
				""")
			.param((double) olderThan.toSeconds())
			.update();
	}

	private static AdReport mapRow(ResultSet rs, int rowNum) throws SQLException {
		AdReport report = new AdReport();
		report.setId(rs.getLong("id"));
		report.setCreatedAt(JdbcBanRepository.instant(rs, "created_at"));
		report.setPartyId(rs.getString("party_id"));
		report.setHostName(rs.getString("host_name"));
		report.setHostNameRaw(rs.getString("host_name_raw"));
		report.setHostAccountHash(JdbcBanRepository.nullableLong(rs, "host_account_hash"));
		report.setActivity(rs.getString("activity"));
		report.setDescription(rs.getString("description"));
		report.setWorld(rs.getString("world"));
		report.setCapacity(nullableInt(rs, "capacity"));
		report.setPartySize(nullableInt(rs, "party_size"));
		report.setInviteCode(rs.getString("invite_code"));
		report.setAdSnapshot(rs.getString("ad_snapshot"));
		report.setReporterName(rs.getString("reporter_name"));
		report.setReporterAccountHash(JdbcBanRepository.nullableLong(rs, "reporter_account_hash"));
		report.setReporterSessionId(rs.getString("reporter_session_id"));
		report.setReporterIpHash(rs.getString("reporter_ip_hash"));
		report.setStatus(rs.getString("status"));
		report.setReviewedAt(JdbcBanRepository.instant(rs, "reviewed_at"));
		report.setReviewedByDiscordId(rs.getString("reviewed_by_discord_id"));
		report.setReviewedByDiscordName(rs.getString("reviewed_by_discord_name"));
		report.setNotified(rs.getBoolean("notified"));
		report.setDiscordChannelId(rs.getString("discord_channel_id"));
		report.setDiscordMessageId(rs.getString("discord_message_id"));
		report.setResultingBanId(JdbcBanRepository.nullableLong(rs, "resulting_ban_id"));
		return report;
	}

	private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}
}

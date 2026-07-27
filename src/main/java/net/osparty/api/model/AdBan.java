package net.osparty.api.model;

import java.time.Instant;
import lombok.Data;

/**
 * A shadowban on an ad-board advertiser: their advertisements are hidden from every other client
 * while remaining fully visible and functional in their own.
 *
 * <p>The subject is a normalized host name, an account hash, or both. Neither alone is sufficient
 * across the whole population — every advertisement carries a host name, but only clients new
 * enough to report an account hash carry one — so a ban matches on either.
 *
 * <p>Revocation is a soft delete ({@code revokedAt}). The row survives so that the audit trail
 * answers "who unbanned this, and why" as readily as "who banned it", and so a repeat offender's
 * ban/unban history stays visible to the next moderator who looks.
 */
@Data
public class AdBan {
	private long id;
	/** Normalized via {@code PartyFactory.normalizeHost}; empty when banned by account hash alone. */
	private String hostName;
	/** The name as advertised, for display in Discord. */
	private String hostNameRaw;
	/** Null when the reporting client never supplied one. */
	private Long accountHash;
	private String reason;
	private Instant createdAt;
	private String createdByDiscordId;
	private String createdByDiscordName;
	private Long sourceReportId;
	/** Null while the ban is active. */
	private Instant revokedAt;
	private String revokedByDiscordId;
	private String revokedByDiscordName;
	private String revokeReason;

	public boolean isActive() {
		return revokedAt == null;
	}
}

package net.osparty.api.model;

import java.time.Instant;
import lombok.Data;

/**
 * A player-submitted report about one advertisement.
 *
 * <p>The subject fields are a denormalised snapshot rather than a reference. Advertisements expire
 * from Redis after 90 seconds, so by the time a moderator opens Discord and clicks a button the
 * advertisement no longer exists anywhere else; this row, and {@link #adSnapshot} in particular, is
 * the only surviving record of what was actually reported. The ban endpoint resolves its subject
 * from here, which is also why the Discord button carries only a report id.
 *
 * <p>The reporter fields are all self-asserted. This system has no account authentication: the
 * {@code identify} frame is client-supplied and host keys are client-minted. They are stored to
 * make abuse patterns visible after the fact, never to authorise anything.
 */
@Data
public class AdReport {
	public static final String STATUS_PENDING = "PENDING";
	public static final String STATUS_BANNED = "BANNED";
	public static final String STATUS_DISMISSED = "DISMISSED";

	private long id;
	private Instant createdAt;

	private String partyId;
	/** Normalized via {@code AdvertisementFactory.normalizeHost}. */
	private String hostName;
	private String hostNameRaw;
	private Long hostAccountHash;
	private String activity;
	private String description;
	private String world;
	private Integer capacity;
	private Integer partySize;
	private String inviteCode;
	/** The whole advertisement as serialized JSON — immutable evidence. */
	private String adSnapshot;

	private String reporterName;
	private Long reporterAccountHash;
	private String reporterSessionId;
	/** sha256(ip + pepper). Raw addresses are never persisted. */
	private String reporterIpHash;

	private String status = STATUS_PENDING;
	private Instant reviewedAt;
	private String reviewedByDiscordId;
	private String reviewedByDiscordName;
	private boolean notified;
	private String discordChannelId;
	private String discordMessageId;
	private Long resultingBanId;
}

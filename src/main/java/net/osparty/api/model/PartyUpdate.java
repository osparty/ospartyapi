package net.osparty.api.model;

import java.util.List;
import lombok.Data;

/**
 * A partial update to an existing ad (host-only). Every field is nullable: a
 * {@code null} means "leave this as-is", so a caller sends only what changed.
 * Used by both {@code PUT /api/v1/parties/{id}} and the WebSocket {@code update}
 * message. Identity fields (id/host/activity/passphrase/inviteCode) are not here
 * — those can't change after creation.
 *
 * <p>Replaces the old fixed-field heartbeat: liveness now comes from the host's
 * open socket, so this carries only genuine field changes.
 */
@Data
public class PartyUpdate
{
	private Integer size;
	private String world;
	private String layout;
	/** Roles still open, as role ids (replaces the previous set when present). */
	private List<String> neededRoles;
	private String description;
	private Integer capacity;
	private String lootRule;
	private Boolean ironmanOnly;
	private Boolean privateParty;
	private Integer minKillCount;
	private Integer minHardModeKillCount;
	private Integer invocation;
	private Boolean hardMode;
}

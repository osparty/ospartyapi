package net.osparty.api.model;

import java.util.List;
import lombok.Data;

@Data
public class Party {
	private String id;
	private String activity;
	private String host;
	/**
	 * The current host's account hash, or 0 when the client never reported one. Kept as a field of
	 * its own rather than read back from {@code members.get(0)}: a host transfer rewrites
	 * {@code host} without touching the member list, so member zero goes stale the moment a party
	 * changes hands.
	 */
	private long hostAccountHash;
	private String description;
	private int size;
	private int capacity;
	private String world;
	private String layout;
	private boolean hardMode;
	private int invocation;
	/** Chambers of Xeric team-size scaling as advertised (e.g. "3+4"); null/empty when unset. */
	private String coxScale;
	private long createdAt;
	private String passphrase;
	private int minKillCount;
	private int minHardModeKillCount;
	private List<Member> members;
	private boolean privateParty;
	private String inviteCode;
	private String lootRule;
	private boolean ironmanOnly;
	private String hostAccountType;
	private List<String> requiredRoles;
	private String hostRole;
	private List<String> neededRoles;
	private boolean learner;
	private boolean teacher;
	private String discordChannelId;
	private String discordInviteUrl;

	public static Party copyOf(Party src) {
		Party c = new Party();
		c.id = src.id;
		c.activity = src.activity;
		c.host = src.host;
		c.hostAccountHash = src.hostAccountHash;
		c.description = src.description;
		c.size = src.size;
		c.capacity = src.capacity;
		c.world = src.world;
		c.layout = src.layout;
		c.hardMode = src.hardMode;
		c.invocation = src.invocation;
		c.coxScale = src.coxScale;
		c.createdAt = src.createdAt;
		c.passphrase = src.passphrase;
		c.minKillCount = src.minKillCount;
		c.minHardModeKillCount = src.minHardModeKillCount;
		c.members = src.members;
		c.privateParty = src.privateParty;
		c.inviteCode = src.inviteCode;
		c.lootRule = src.lootRule;
		c.ironmanOnly = src.ironmanOnly;
		c.hostAccountType = src.hostAccountType;
		c.requiredRoles = src.requiredRoles;
		c.hostRole = src.hostRole;
		c.neededRoles = src.neededRoles;
		c.learner = src.learner;
		c.teacher = src.teacher;
		c.discordChannelId = src.discordChannelId;
		c.discordInviteUrl = src.discordInviteUrl;
		return c;
	}
}

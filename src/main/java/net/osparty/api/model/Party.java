package net.osparty.api.model;

import java.util.List;
import lombok.Data;

@Data
public class Party {
	private String id;
	private String activity;
	private String host;
	private String description;
	private int size;
	private int capacity;
	private String world;
	private String layout;
	private boolean hardMode;
	private int invocation;
	private long createdAt;
	private String passphrase;
	private int minKillCount;
	private int minHardModeKillCount;
	private List<String> members;
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

	/**
	 * A shallow field copy. Used by the reconciler to retain an immutable snapshot of an ad for the
	 * next tick's diff, so it never aliases a repository-owned instance that may be mutated in place.
	 */
	public static Party copyOf(Party src) {
		Party c = new Party();
		c.id = src.id;
		c.activity = src.activity;
		c.host = src.host;
		c.description = src.description;
		c.size = src.size;
		c.capacity = src.capacity;
		c.world = src.world;
		c.layout = src.layout;
		c.hardMode = src.hardMode;
		c.invocation = src.invocation;
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
		return c;
	}
}

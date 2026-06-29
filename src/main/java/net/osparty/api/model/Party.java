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
}

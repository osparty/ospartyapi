package net.osparty.api.model;

import java.util.List;

public record PartyRequest(
	String activity,
	String host,
	long hostAccountHash,
	String description,
	int capacity,
	String world,
	int minKillCount,
	int minHardModeKillCount,
	String passphrase,
	boolean privateParty,
	String lootRule,
	boolean ironmanOnly,
	String hostAccountType,
	boolean hardMode,
	int invocation,
	String coxScale,
	List<String> requiredRoles,
	String hostRole,
	boolean learner,
	boolean teacher) {
}

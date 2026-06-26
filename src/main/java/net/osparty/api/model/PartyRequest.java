package net.osparty.api.model;

/**
 * Payload the plugin POSTs to advertise a new party. Mirrors the plugin's
 * {@code net.osparty.model.PartyRequest}. The {@code passphrase} is generated
 * client-side (the host's RuneLite party passphrase) and simply stored; the
 * {@code inviteCode} is generated server-side, so it is not part of this request.
 */
public record PartyRequest(
	String activity,
	String host,
	String description,
	int capacity,
	String world,
	int minKillCount,
	int minHardModeKillCount,
	String passphrase,
	boolean privateParty,
	String lootRule,
	boolean ironmanOnly,
	String hostAccountType)
{
}

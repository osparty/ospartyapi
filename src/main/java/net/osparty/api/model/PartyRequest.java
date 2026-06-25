package net.osparty.api.model;

/**
 * Payload the plugin POSTs to advertise a new party. Mirrors the plugin's
 * {@code com.aioparty.model.PartyRequest}. The {@code passphrase} is generated
 * client-side (the host's RuneLite party passphrase) and simply stored.
 */
public record PartyRequest(
	String activity,
	String host,
	String description,
	int capacity,
	String world,
	int minKillCount,
	int minHardModeKillCount,
	String passphrase)
{
}

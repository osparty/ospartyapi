package net.osparty.api.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

/**
 * A client asking for an ad to be put on the board. Inbound only, which is why {@code privateAd} takes a
 * {@link JsonAlias} rather than the getter/setter pair {@link Advertisement} needs.
 */
public record AdvertisementRequest(
	String activity,
	String host,
	long hostAccountHash,
	String description,
	int capacity,
	String world,
	int minKillCount,
	int minHardModeKillCount,
	String passphrase,
	/** Aliased for plugin 1.0.50, which still sends the old name. Goes with {@code CapabilitiesController}. */
	@JsonAlias("privateParty") boolean privateAd,
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

	/**
	 * Fold "no account" to the zero this service treats as unknown.
	 *
	 * <p>The plugin sends {@code -1} when nobody is logged in, because that is what
	 * {@code Client.getAccountHash()} returns, while everything downstream tests {@code hostAccountHash != 0}.
	 * Without this, an ad hosted from a logged-out client is a "known" account -- and every such ad shares
	 * the same one, so they match each other in bans, block lists and party history.
	 */
	public AdvertisementRequest {
		if (hostAccountHash == -1L) {
			hostAccountHash = 0L;
		}
	}
}

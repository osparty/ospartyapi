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
}

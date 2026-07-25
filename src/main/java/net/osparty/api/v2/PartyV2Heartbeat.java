package net.osparty.api.v2;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps this node's ownership alive (PARTY_V2_MIGRATION.md §10). Every renewal bumps the {@code pv2:owner:}
 * lock + {@code pv2:party:} hash TTLs for each room owned here; if this node stops (crash/kill), it stops
 * renewing, the locks expire, and the rooms become claimable by whichever node the clients reconnect to.
 *
 * <p>Renews well inside the 30 s TTL so a transient Redis blip doesn't drop a still-live owner. Skipped in
 * the {@code test} profile (single-node ownership never expires).
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2Heartbeat {
	private final PartyV2Manager manager;
	private final PartyOwnershipService ownership;

	public PartyV2Heartbeat(PartyV2Manager manager, PartyOwnershipService ownership) {
		this.manager = manager;
		this.ownership = ownership;
	}

	@Scheduled(fixedRate = 10_000)
	void renewOwned() {
		for (String room : manager.ownedRoomIds()) {
			ownership.renew(room);
		}
	}
}

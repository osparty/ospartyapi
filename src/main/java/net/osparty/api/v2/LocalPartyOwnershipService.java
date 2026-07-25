package net.osparty.api.v2;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node ownership: this instance owns every room it hosts, and no room is ever owned elsewhere. Used
 * in tests and any deployment without Redis (the P1 single-node model). {@link RedisPartyOwnershipService}
 * is the multi-node implementation.
 */
@Component
@Profile("test")
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class LocalPartyOwnershipService implements PartyOwnershipService {
	private final String nodeId;
	private final Set<String> owned = ConcurrentHashMap.newKeySet();

	public LocalPartyOwnershipService(NodeIdentity node) {
		this.nodeId = node.nodeId();
	}

	@Override
	public Claim claim(String room) {
		return owned.add(room) ? Claim.CLAIMED : Claim.ALREADY_OWNED_BY_SELF;
	}

	@Override
	public Optional<Owner> lookup(String room) {
		return owned.contains(room) ? Optional.of(new Owner(nodeId)) : Optional.empty();
	}

	@Override
	public void renew(String room) {
		// Nothing to renew: single-node ownership never expires.
	}

	@Override
	public void release(String room) {
		owned.remove(room);
	}
}

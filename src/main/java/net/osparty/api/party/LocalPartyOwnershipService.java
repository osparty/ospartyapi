package net.osparty.api.party;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node ownership: this instance owns every room it hosts, and no room is ever owned elsewhere. Used
 * in tests and any deployment without Redis (the P1 single-node model). {@link RedisPartyOwnershipService}
 * is the multi-node implementation.
 */
@Component
@Profile("test")
public class LocalPartyOwnershipService implements PartyOwnershipService {
	private final String nodeId;
	private final Set<String> owned = ConcurrentHashMap.newKeySet();
	/** Rooms released for handover: no owner, but not gone either. Cleared by a re-claim or a release. */
	private final Set<String> handingOver = ConcurrentHashMap.newKeySet();

	public LocalPartyOwnershipService(NodeIdentity node) {
		this.nodeId = node.nodeId();
	}

	@Override
	public Claim claim(String room) {
		handingOver.remove(room);
		return owned.add(room) ? Claim.CLAIMED : Claim.ALREADY_OWNED_BY_SELF;
	}

	@Override
	public Optional<Owner> lookup(String room) {
		return owned.contains(room) ? Optional.of(new Owner(nodeId)) : Optional.empty();
	}

	@Override
	public boolean renew(String room) {
		// Nothing to renew: single-node ownership never expires.
		return owned.contains(room);
	}

	@Override
	public boolean ownedBySelf(String room) {
		return owned.contains(room);
	}

	@Override
	public void release(String room) {
		owned.remove(room);
		handingOver.remove(room);
	}

	@Override
	public void releaseForHandover(String room) {
		if (owned.remove(room)) {
			handingOver.add(room);
		}
	}

	@Override
	public boolean handoverPending(String room) {
		return handingOver.contains(room);
	}

	@Override
	public Set<String> reclaimExpired() {
		// Single-node: a room with no owner is one this node handed over and nobody took back. There is no
		// other node that could have claimed it in the meantime, so every one of them is ours to reclaim.
		Set<String> claimed = new LinkedHashSet<>(handingOver);
		claimed.forEach(this::claim);
		return claimed;
	}
}

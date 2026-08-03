package net.osparty.api.party;

import static org.assertj.core.api.Assertions.assertThat;

import net.osparty.api.party.PartyOwnershipService.Claim;
import org.junit.jupiter.api.Test;

/** Single-node ownership: a room is owned once claimed, re-claim is idempotent, release frees it. */
class LocalPartyOwnershipServiceTest {
	private final NodeIdentity node = new NodeIdentity("node-a", true);
	private final LocalPartyOwnershipService ownership = new LocalPartyOwnershipService(node);

	@Test
	void claimThenReclaimIsIdempotent() {
		assertThat(ownership.claim("r")).isEqualTo(Claim.CLAIMED);
		assertThat(ownership.claim("r")).isEqualTo(Claim.ALREADY_OWNED_BY_SELF);
		assertThat(ownership.lookup("r")).map(PartyOwnershipService.Owner::nodeId).contains("node-a");
	}

	@Test
	void unknownRoomHasNoOwner() {
		assertThat(ownership.lookup("nope")).isEmpty();
	}

	@Test
	void releaseFreesTheRoom() {
		ownership.claim("r");
		ownership.release("r");
		assertThat(ownership.lookup("r")).isEmpty();
		assertThat(ownership.claim("r")).isEqualTo(Claim.CLAIMED);
	}

	@Test
	void releaseEndsTheRoomOutright() {
		ownership.claim("r");
		ownership.release("r");
		// A released room is over, not in transit — a joiner must be told it is gone, not to wait.
		assertThat(ownership.handoverPending("r")).isFalse();
	}

	@Test
	void handoverLeavesTheRoomOwnerlessButPending() {
		ownership.claim("r");
		ownership.releaseForHandover("r");
		assertThat(ownership.lookup("r")).isEmpty();
		assertThat(ownership.handoverPending("r")).isTrue();
	}

	@Test
	void reclaimingEndsTheHandover() {
		ownership.claim("r");
		ownership.releaseForHandover("r");
		assertThat(ownership.claim("r")).isEqualTo(Claim.CLAIMED);
		assertThat(ownership.handoverPending("r")).isFalse();
	}

	@Test
	void unknownRoomIsNotPending() {
		assertThat(ownership.handoverPending("nope")).isFalse();
	}
}

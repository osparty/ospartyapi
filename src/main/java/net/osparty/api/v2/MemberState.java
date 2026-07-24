package net.osparty.api.v2;

import com.fasterxml.jackson.databind.JsonNode;
import net.osparty.api.v2.protocol.Outbound;

/**
 * One member's live presence in a {@link LivePartyRoom}. Identity and roster {@link Status} are
 * server-authoritative (assigned/changed only by the owner node); {@link #live} is the last opaque state
 * snapshot the member sent (a serialised plugin {@code PlayerUpdate}), relayed to peers without
 * interpretation. Held entirely in RAM — never written to Redis (PARTY_V2_MIGRATION.md §10/§11).
 */
final class MemberState {
	enum Status { HOST, MEMBER, PENDING }

	final long memberId;
	volatile String name;
	volatile long accountHash;
	volatile Status status;
	volatile String role;
	volatile boolean learner;
	volatile boolean teacher;
	volatile boolean invited;
	/** Last state payload from this member; null until they first send one. */
	volatile JsonNode live;

	MemberState(long memberId, String name, long accountHash, Status status) {
		this.memberId = memberId;
		this.name = name;
		this.accountHash = accountHash;
		this.status = status;
	}

	Outbound.RosterEntry toRosterEntry() {
		return new Outbound.RosterEntry(memberId, name, accountHash, status.name(), role, learner, teacher);
	}
}

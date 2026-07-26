package net.osparty.api.v2;

import net.osparty.api.v2.protocol.Outbound;

/**
 * One member's live presence in a {@link LivePartyRoom}. Identity and roster {@link Status} are
 * server-authoritative (assigned/changed only by the owner node). Held entirely in RAM — never written to
 * Redis (PARTY_V2_MIGRATION.md §10/§11).
 *
 * <p>Deliberately holds <em>no</em> live state. Member snapshots are relayed to peers and forgotten; a
 * member seated later gets its baseline from a {@code resync} the peers answer, not from anything stored
 * here (PARTY_V2_OPTIMIZATION.md §5.2). Storing the last snapshot was wrong the moment frames stopped being
 * complete — the owner would have been replaying a fragment while believing it a full picture.
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

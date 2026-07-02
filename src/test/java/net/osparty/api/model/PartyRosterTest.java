package net.osparty.api.model;

import java.util.List;
import net.osparty.api.service.PartyFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host advertises its live roster (host + admitted members, with accountHashes) via
 * heartbeat so search clients can block/favourite-match any member. These cover the two
 * server-side pieces: applying the roster patch, and diffing it into a broadcast delta.
 */
class PartyRosterTest {

	private Party party(List<Member> members) {
		Party p = new Party();
		p.setId("p1");
		p.setActivity("cox");
		p.setMembers(members);
		return p;
	}

	@Test
	void applyUpdateReplacesRosterWhenChanged() {
		Party p = party(List.of(new Member("Host", 1L)));
		PartyUpdate u = new PartyUpdate();
		u.setMembers(List.of(new Member("Host", 1L), new Member("Joiner", -7L)));

		assertTrue(PartyFactory.applyUpdate(p, u));
		assertEquals(2, p.getMembers().size());
		assertEquals(-7L, p.getMembers().get(1).getAccountHash());
	}

	@Test
	void applyUpdateIgnoresUnchangedRoster() {
		Party p = party(List.of(new Member("Host", 1L)));
		PartyUpdate u = new PartyUpdate();
		u.setMembers(List.of(new Member("Host", 1L)));

		assertFalse(PartyFactory.applyUpdate(p, u));
	}

	@Test
	void diffCarriesRosterChange() {
		Party prev = party(List.of(new Member("Host", 1L)));
		Party cur = party(List.of(new Member("Host", 1L), new Member("Joiner", -7L)));

		PartyDelta delta = PartyDelta.diff(prev, cur);
		assertNotNull(delta);
		assertNotNull(delta.members());
		assertEquals(2, delta.members().size());
	}

	@Test
	void diffNullWhenRosterUnchanged() {
		Party prev = party(List.of(new Member("Host", 1L)));
		Party cur = party(List.of(new Member("Host", 1L)));

		assertNull(PartyDelta.diff(prev, cur));
	}
}

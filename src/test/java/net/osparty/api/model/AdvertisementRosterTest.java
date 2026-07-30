package net.osparty.api.model;

import java.util.List;
import net.osparty.api.service.AdvertisementFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvertisementRosterTest {

	private Advertisement party(List<Member> members) {
		Advertisement p = new Advertisement();
		p.setId("p1");
		p.setActivity("cox");
		p.setMembers(members);
		return p;
	}

	@Test
	void applyUpdateReplacesRosterWhenChanged() {
		Advertisement p = party(List.of(new Member("Host", 1L)));
		AdvertisementUpdate u = new AdvertisementUpdate();
		u.setMembers(List.of(new Member("Host", 1L), new Member("Joiner", -7L)));

		assertTrue(AdvertisementFactory.applyUpdate(p, u));
		assertEquals(2, p.getMembers().size());
		assertEquals(-7L, p.getMembers().get(1).getAccountHash());
	}

	@Test
	void applyUpdateIgnoresUnchangedRoster() {
		Advertisement p = party(List.of(new Member("Host", 1L)));
		AdvertisementUpdate u = new AdvertisementUpdate();
		u.setMembers(List.of(new Member("Host", 1L)));

		assertFalse(AdvertisementFactory.applyUpdate(p, u));
	}

	@Test
	void applyUpdateNeverDowngradesKnownHashToZero() {
		Advertisement p = party(List.of(new Member("Host", 42L)));
		AdvertisementUpdate u = new AdvertisementUpdate();
		u.setMembers(List.of(new Member("Host", 0L), new Member("Joiner", -7L)));

		assertTrue(AdvertisementFactory.applyUpdate(p, u));
		assertEquals(42L, p.getMembers().get(0).getAccountHash());
		assertEquals(-7L, p.getMembers().get(1).getAccountHash());
	}

	@Test
	void applyUpdateIgnoresRosterThatOnlyDropsHashes() {
		Advertisement p = party(List.of(new Member("Host", 42L)));
		AdvertisementUpdate u = new AdvertisementUpdate();
		u.setMembers(List.of(new Member("Host", 0L)));

		assertFalse(AdvertisementFactory.applyUpdate(p, u));
		assertEquals(42L, p.getMembers().get(0).getAccountHash());
	}

	@Test
	void diffCarriesRosterChange() {
		Advertisement prev = party(List.of(new Member("Host", 1L)));
		Advertisement cur = party(List.of(new Member("Host", 1L), new Member("Joiner", -7L)));

		AdvertisementDelta delta = AdvertisementDelta.diff(prev, cur);
		assertNotNull(delta);
		assertNotNull(delta.members());
		assertEquals(2, delta.members().size());
	}

	@Test
	void diffNullWhenRosterUnchanged() {
		Advertisement prev = party(List.of(new Member("Host", 1L)));
		Advertisement cur = party(List.of(new Member("Host", 1L)));

		assertNull(AdvertisementDelta.diff(prev, cur));
	}
}

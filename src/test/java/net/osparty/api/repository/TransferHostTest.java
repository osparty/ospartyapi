package net.osparty.api.repository;

import java.util.List;
import net.osparty.api.model.Member;
import net.osparty.api.model.Advertisement;
import net.osparty.api.model.AdvertisementDelta;
import net.osparty.api.model.AdvertisementRequest;
import net.osparty.api.repository.AdvertisementRepository.Authorization;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferHostTest {

	private AdvertisementRequest request(String host) {
		return new AdvertisementRequest("cox", host, 1L, "trio", 3, "301", 0, 0, "pp-xfer",
			false, null, false, null, false, 0, null, null, null, false, false);
	}

	@Test
	void transferHostReKeysAndMovesIndex() {
		FakeAdvertisementRepository repo = new FakeAdvertisementRepository();
		Advertisement party = repo.create(request("OldHost"), "k-old");
		String id = party.getId();
		assertEquals(Authorization.OK, repo.authorize(id, "k-old"));

		Advertisement updated = repo.transferHost(id, "NewHost", "k-new").orElseThrow();

		assertEquals(id, updated.getId());
		assertEquals("NewHost", updated.getHost());
		assertEquals(Authorization.FORBIDDEN, repo.authorize(id, "k-old"));
		assertEquals(Authorization.OK, repo.authorize(id, "k-new"));
		assertTrue(repo.findByHost("NewHost").isPresent());
		assertTrue(repo.findByHost("OldHost").isEmpty());
	}

	@Test
	void transferHostOnMissingAdvertisementReturnsEmpty() {
		FakeAdvertisementRepository repo = new FakeAdvertisementRepository();
		assertTrue(repo.transferHost("nope", "NewHost", "k-new").isEmpty());
	}

	@Test
	void diffCarriesHostChange() {
		Advertisement prev = new Advertisement();
		prev.setId("p1");
		prev.setActivity("cox");
		prev.setHost("OldHost");
		prev.setMembers(List.of(new Member("OldHost", 1L)));
		Advertisement cur = new Advertisement();
		cur.setId("p1");
		cur.setActivity("cox");
		cur.setHost("NewHost");
		cur.setMembers(List.of(new Member("OldHost", 1L)));

		AdvertisementDelta delta = AdvertisementDelta.diff(prev, cur);
		assertNotNull(delta);
		assertEquals("NewHost", delta.host());
	}

	@Test
	void diffNullWhenHostUnchanged() {
		Advertisement prev = new Advertisement();
		prev.setId("p1");
		prev.setHost("Host");
		Advertisement cur = new Advertisement();
		cur.setId("p1");
		cur.setHost("Host");

		assertNull(AdvertisementDelta.diff(prev, cur));
	}
}

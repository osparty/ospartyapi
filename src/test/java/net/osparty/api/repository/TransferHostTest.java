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

		Advertisement updated = repo.transferHost(id, "NewHost", "IRONMAN", "k-new").orElseThrow();

		assertEquals(id, updated.getId());
		assertEquals("NewHost", updated.getHost());
		assertEquals("IRONMAN", updated.getHostAccountType());
		assertEquals(Authorization.FORBIDDEN, repo.authorize(id, "k-old"));
		assertEquals(Authorization.OK, repo.authorize(id, "k-new"));
		assertTrue(repo.findByHost("NewHost").isPresent());
		assertTrue(repo.findByHost("OldHost").isEmpty());
	}

	@Test
	void transferHostOnMissingAdvertisementReturnsEmpty() {
		FakeAdvertisementRepository repo = new FakeAdvertisementRepository();
		assertTrue(repo.transferHost("nope", "NewHost", "NORMAL", "k-new").isEmpty());
	}

	/**
	 * An unknown account type is stored as NORMAL, not left absent: the old host's badge has to go, and a
	 * cleared field is indistinguishable from an unchanged one on the delta a resuming client receives.
	 */
	@Test
	void transferHostWithoutAnAccountTypeClearsTheOutgoingHostsBadge() {
		FakeAdvertisementRepository repo = new FakeAdvertisementRepository();
		Advertisement party = repo.create(request("OldHost"), "k-old");
		party.setHostAccountType("HARDCORE_IRONMAN");

		Advertisement updated = repo.transferHost(party.getId(), "NewHost", null, "k-new").orElseThrow();

		assertEquals("NORMAL", updated.getHostAccountType());
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

	/**
	 * The member list is deliberately left alone across the transfer, which is what makes member zero
	 * the wrong answer: without the hash on the delta a client reading it back from the roster keeps
	 * matching blocks and favourites against the host that just handed the party over.
	 */
	@Test
	void diffCarriesHostAccountHashWhenTheHostChanges() {
		Advertisement prev = new Advertisement();
		prev.setId("p1");
		prev.setActivity("cox");
		prev.setHost("OldHost");
		prev.setHostAccountHash(1L);
		prev.setMembers(List.of(new Member("OldHost", 1L)));
		Advertisement cur = new Advertisement();
		cur.setId("p1");
		cur.setActivity("cox");
		cur.setHost("NewHost");
		cur.setHostAccountHash(2L);
		cur.setMembers(List.of(new Member("OldHost", 1L)));

		AdvertisementDelta delta = AdvertisementDelta.diff(prev, cur);
		assertNotNull(delta);
		assertEquals(2L, delta.hostAccountHash());
	}

	/** Same story as the hash for the badge beside the name: it is stamped on the ad, not read off a member. */
	@Test
	void diffCarriesHostAccountTypeWhenTheHostChanges() {
		Advertisement prev = new Advertisement();
		prev.setId("p1");
		prev.setActivity("cox");
		prev.setHost("OldHost");
		prev.setHostAccountType("IRONMAN");
		Advertisement cur = new Advertisement();
		cur.setId("p1");
		cur.setActivity("cox");
		cur.setHost("NewHost");
		cur.setHostAccountType("NORMAL");

		AdvertisementDelta delta = AdvertisementDelta.diff(prev, cur);
		assertNotNull(delta);
		assertEquals("NORMAL", delta.hostAccountType());
	}

	@Test
	void diffOmitsHostAccountHashWhenUnchanged() {
		Advertisement prev = new Advertisement();
		prev.setId("p1");
		prev.setHost("OldHost");
		prev.setHostAccountHash(7L);
		Advertisement cur = new Advertisement();
		cur.setId("p1");
		cur.setHost("NewHost");
		cur.setHostAccountHash(7L);

		AdvertisementDelta delta = AdvertisementDelta.diff(prev, cur);
		assertNotNull(delta);
		assertNull(delta.hostAccountHash());
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

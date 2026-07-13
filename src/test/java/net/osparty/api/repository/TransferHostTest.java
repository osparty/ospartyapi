package net.osparty.api.repository;

import java.util.List;
import net.osparty.api.model.Member;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyDelta;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.repository.PartyRepository.Authorization;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferHostTest {

	private PartyRequest request(String host) {
		return new PartyRequest("cox", host, 1L, "trio", 3, "301", 0, 0, "pp-xfer",
			false, null, false, null, false, 0, null, null, null, false, false);
	}

	@Test
	void transferHostReKeysAndMovesIndex() {
		FakePartyRepository repo = new FakePartyRepository();
		Party party = repo.create(request("OldHost"), "k-old");
		String id = party.getId();
		assertEquals(Authorization.OK, repo.authorize(id, "k-old"));

		Party updated = repo.transferHost(id, "NewHost", "k-new").orElseThrow();

		assertEquals(id, updated.getId());
		assertEquals("NewHost", updated.getHost());
		assertEquals(Authorization.FORBIDDEN, repo.authorize(id, "k-old"));
		assertEquals(Authorization.OK, repo.authorize(id, "k-new"));
		assertTrue(repo.findByHost("NewHost").isPresent());
		assertTrue(repo.findByHost("OldHost").isEmpty());
	}

	@Test
	void transferHostOnMissingPartyReturnsEmpty() {
		FakePartyRepository repo = new FakePartyRepository();
		assertTrue(repo.transferHost("nope", "NewHost", "k-new").isEmpty());
	}

	@Test
	void diffCarriesHostChange() {
		Party prev = new Party();
		prev.setId("p1");
		prev.setActivity("cox");
		prev.setHost("OldHost");
		prev.setMembers(List.of(new Member("OldHost", 1L)));
		Party cur = new Party();
		cur.setId("p1");
		cur.setActivity("cox");
		cur.setHost("NewHost");
		cur.setMembers(List.of(new Member("OldHost", 1L)));

		PartyDelta delta = PartyDelta.diff(prev, cur);
		assertNotNull(delta);
		assertEquals("NewHost", delta.host());
	}

	@Test
	void diffNullWhenHostUnchanged() {
		Party prev = new Party();
		prev.setId("p1");
		prev.setHost("Host");
		Party cur = new Party();
		cur.setId("p1");
		cur.setHost("Host");

		assertNull(PartyDelta.diff(prev, cur));
	}
}

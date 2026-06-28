package net.osparty.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import org.junit.jupiter.api.Test;

/** Plain unit tests for the in-memory store's eviction + host-uniqueness rules. */
class InMemoryPartyRepositoryTest
{
	private PartyRequest req(String host, String activity)
	{
		return new PartyRequest(activity, host, null, 2, null, 0, 0, "p", false, "FFA", false, "NORMAL", false, 0,
			null, null);
	}

	@Test
	void freshAdsSurviveButStaleOnesAreEvicted()
	{
		InMemoryPartyRepository store = new InMemoryPartyRepository();
		store.create(req("Host", "cox"));

		// Generous TTL: nothing is stale yet.
		assertEquals(0, store.evictStale(60_000));
		assertEquals(1, store.list(null).size());

		// A negative max-age pushes the cutoff into the future, so everything is stale.
		assertEquals(1, store.evictStale(-1));
		assertTrue(store.list(null).isEmpty());
	}

	@Test
	void reAdvertisingReplacesTheHostsPreviousAd()
	{
		InMemoryPartyRepository store = new InMemoryPartyRepository();
		store.create(req("Dup", "cox"));
		store.create(req("Dup", "tob"));

		assertEquals(1, store.list(null).size());
		assertEquals("tob", store.list(null).get(0).getActivity());
	}

	@Test
	void privatePartiesAreHiddenFromListButFoundByCode()
	{
		InMemoryPartyRepository store = new InMemoryPartyRepository();
		store.create(req("Pub", "cox"));
		Party priv = store.create(
			new PartyRequest("toa", "Priv", null, 2, null, 0, 0, "p", true, "split", true, "IRONMAN", false, 300,
				null, null));

		// Public list excludes the private party.
		assertEquals(1, store.list(null).size());
		assertEquals("Pub", store.list(null).get(0).getHost());

		// But it's reachable by its (case-insensitive) invite code, with fields intact.
		assertTrue(priv.isPrivateParty());
		assertEquals("SPLIT", priv.getLootRule());   // normalized to upper-case
		assertTrue(priv.isIronmanOnly());
		assertTrue(store.findByInviteCode(priv.getInviteCode().toLowerCase()).isPresent());
		assertEquals("Priv", store.findByInviteCode(priv.getInviteCode()).get().getHost());
		assertFalse(store.findByInviteCode("nope").isPresent());
	}
}

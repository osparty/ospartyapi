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
		store.create(req("Host", "cox"), "key");

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
		store.create(req("Dup", "cox"), "key");
		store.create(req("Dup", "tob"), "key");

		assertEquals(1, store.list(null).size());
		assertEquals("tob", store.list(null).get(0).getActivity());
	}

	@Test
	void privatePartiesAreHiddenFromListButFoundByCode()
	{
		InMemoryPartyRepository store = new InMemoryPartyRepository();
		store.create(req("Pub", "cox"), "key");
		Party priv = store.create(
			new PartyRequest("toa", "Priv", null, 2, null, 0, 0, "p", true, "split", true, "IRONMAN", false, 300,
				null, null), "key");

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

	@Test
	void hostKeyGatesMutations()
	{
		InMemoryPartyRepository store = new InMemoryPartyRepository();
		Party keyed = store.create(req("Keyed", "cox"), "secret");

		// Only the matching key authorises a host mutation on a key-bearing ad.
		assertEquals(PartyRepository.Authorization.OK, store.authorize(keyed.getId(), "secret"));
		assertEquals(PartyRepository.Authorization.FORBIDDEN, store.authorize(keyed.getId(), "wrong"));
		assertEquals(PartyRepository.Authorization.FORBIDDEN, store.authorize(keyed.getId(), null));
		assertEquals(PartyRepository.Authorization.NOT_FOUND, store.authorize("9999", "secret"));

		// An ad created without a key stays open (back-compat for older clients).
		Party open = store.create(req("Open", "tob"), null);
		assertEquals(PartyRepository.Authorization.OK, store.authorize(open.getId(), null));
		assertEquals(PartyRepository.Authorization.OK, store.authorize(open.getId(), "anything"));
	}
}

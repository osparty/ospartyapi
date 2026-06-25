package net.osparty.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.osparty.api.model.PartyRequest;
import org.junit.jupiter.api.Test;

/** Plain unit tests for the in-memory store's eviction + host-uniqueness rules. */
class InMemoryPartyRepositoryTest
{
	private PartyRequest req(String host, String activity)
	{
		return new PartyRequest(activity, host, null, 2, null, 0, 0, "p");
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
}

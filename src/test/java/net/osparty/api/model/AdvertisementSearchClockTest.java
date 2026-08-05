package net.osparty.api.model;

import net.osparty.api.service.AdvertisementFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock behind "searching 20m", the stale marker and the by-age sorts. It runs from the moment a party
 * starts looking for people, which is when it is advertised — and again when a full one loses someone, since
 * a seat that opened a minute ago is not one that has gone unfilled all afternoon.
 */
class AdvertisementSearchClockTest {
	private static final long ADVERTISED_AT = 1_000_000L;
	private static final long NOW = ADVERTISED_AT + 3 * 60 * 60_000L;

	private static Advertisement party(int size, int capacity) {
		Advertisement ad = new Advertisement();
		ad.setId("p1");
		ad.setActivity("cox");
		ad.setSize(size);
		ad.setCapacity(capacity);
		ad.setCreatedAt(ADVERTISED_AT);
		return ad;
	}

	private static AdvertisementUpdate sized(int size) {
		AdvertisementUpdate patch = new AdvertisementUpdate();
		patch.setSize(size);
		return patch;
	}

	@Test
	void losingSomeoneFromAFullPartyRestartsIt() {
		Advertisement ad = party(5, 5);

		assertTrue(AdvertisementFactory.applyUpdate(ad, sized(4), NOW));

		assertEquals(NOW, ad.getCreatedAt());
	}

	@Test
	void raisingTheTeamSizePastAFullPartyRestartsItToo() {
		// The same thing from the other side: the party did not shrink, the room it has to fill grew.
		Advertisement ad = party(5, 5);
		AdvertisementUpdate patch = new AdvertisementUpdate();
		patch.setCapacity(6);

		assertTrue(AdvertisementFactory.applyUpdate(ad, patch, NOW));

		assertEquals(NOW, ad.getCreatedAt());
	}

	@Test
	void aPartyThatWasNeverFullKeepsLooking() {
		// It has been searching the whole time, and losing someone does not make that less true.
		Advertisement ad = party(3, 5);

		AdvertisementFactory.applyUpdate(ad, sized(2), NOW);

		assertEquals(ADVERTISED_AT, ad.getCreatedAt());
	}

	@Test
	void fillingUpDoesNotRestartIt() {
		Advertisement ad = party(4, 5);

		AdvertisementFactory.applyUpdate(ad, sized(5), NOW);

		assertEquals(ADVERTISED_AT, ad.getCreatedAt());
	}

	@Test
	void anUncappedPartyIsNeverFullAndSoNeverRestarts() {
		Advertisement ad = party(5, 0);

		AdvertisementFactory.applyUpdate(ad, sized(4), NOW);

		assertEquals(ADVERTISED_AT, ad.getCreatedAt());
	}

	@Test
	void theRestartReachesClientsThatAlreadyHoldTheAd() {
		// Only as a delta: a card already on screen is the one claiming the party has searched all day.
		Advertisement before = party(5, 5);
		Advertisement after = party(5, 5);
		AdvertisementFactory.applyUpdate(after, sized(4), NOW);

		AdvertisementDelta delta = AdvertisementDelta.diff(before, after);

		assertNotNull(delta);
		assertEquals(NOW, delta.createdAt());
	}

	@Test
	void anOrdinaryChangeCarriesNoClock() {
		Advertisement before = party(3, 5);
		Advertisement after = party(3, 5);
		AdvertisementFactory.applyUpdate(after, sized(4), NOW);

		AdvertisementDelta delta = AdvertisementDelta.diff(before, after);

		assertNotNull(delta);
		assertNull(delta.createdAt());
	}
}

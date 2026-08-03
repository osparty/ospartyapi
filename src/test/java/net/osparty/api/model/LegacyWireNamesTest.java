package net.osparty.api.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The board wire as plugin 1.0.50 reads and writes it.
 *
 * <p>That release is what the hub serves while 1.0.51 waits on RuneLite's review, and it predates
 * {@code privateParty -> privateAd}. It deserialises with Gson, which drops a name it does not know without
 * an error, so a rename it has not caught up to costs a private ad its flag and says nothing.
 *
 * <p>Delete this file with the aliases, once {@code osparty.ws.connections} shows nobody left on 1.0.50.
 */
class LegacyWireNamesTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void advertisementCarriesBothNames() throws Exception {
		Advertisement ad = new Advertisement();
		ad.setId("a1");
		ad.setPrivateAd(true);

		String json = mapper.writeValueAsString(ad);

		assertTrue(json.contains("\"privateAd\":true"), json);
		assertTrue(json.contains("\"privateParty\":true"), json);
	}

	/** The stored form is this same class through this same mapper, so what it emits it has to read back. */
	@Test
	void advertisementRoundTripsThroughItsOwnOutput() throws Exception {
		Advertisement ad = new Advertisement();
		ad.setId("a1");
		ad.setPrivateAd(true);

		Advertisement back = mapper.readValue(mapper.writeValueAsString(ad), Advertisement.class);

		assertTrue(back.isPrivateAd());
	}

	@Test
	void advertisementAcceptsTheOldNameAlone() throws Exception {
		Advertisement ad = mapper.readValue("{\"id\":\"a1\",\"privateParty\":true}", Advertisement.class);

		assertTrue(ad.isPrivateAd());
	}

	@Test
	void hostRequestAcceptsTheOldName() throws Exception {
		AdvertisementRequest request = mapper.readValue(
			"{\"activity\":\"cox\",\"host\":\"Alice\",\"privateParty\":true}", AdvertisementRequest.class);

		assertTrue(request.privateAd());
	}

	@Test
	void patchAcceptsTheOldName() throws Exception {
		AdvertisementUpdate patch = mapper.readValue("{\"privateParty\":true}", AdvertisementUpdate.class);

		assertEquals(Boolean.TRUE, patch.getPrivateAd());
	}

	@Test
	void deltaCarriesBothNames() throws Exception {
		Advertisement prev = new Advertisement();
		prev.setId("a1");
		Advertisement cur = Advertisement.copyOf(prev);
		cur.setPrivateAd(true);

		String json = mapper.writeValueAsString(AdvertisementDelta.diff(prev, cur));

		assertTrue(json.contains("\"privateAd\":true"), json);
		assertTrue(json.contains("\"privateParty\":true"), json);
	}

	/** NON_NULL still applies to the alias: an unchanged flag must not look like a change to false. */
	@Test
	void deltaOmitsBothNamesWhenTheFlagDidNotMove() throws Exception {
		Advertisement prev = new Advertisement();
		prev.setId("a1");
		prev.setPrivateAd(true);
		Advertisement cur = Advertisement.copyOf(prev);
		cur.setWorld("302");

		String json = mapper.writeValueAsString(AdvertisementDelta.diff(prev, cur));

		assertFalse(json.contains("privateAd"), json);
		assertFalse(json.contains("privateParty"), json);
	}

	@Test
	void memberListsSurviveBothNames() throws Exception {
		Advertisement ad = new Advertisement();
		ad.setId("a1");
		ad.setMembers(List.of(new Member("Alice", 1L)));

		Advertisement back = mapper.readValue(mapper.writeValueAsString(ad), Advertisement.class);

		assertEquals(1, back.getMembers().size());
		assertEquals("Alice", back.getMembers().get(0).getName());
	}
}

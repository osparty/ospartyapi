package net.osparty.api.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberBadgesTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void badgesAreIgnoredOnInboundJson() throws Exception {
		Member m = mapper.readValue(
			"{\"name\":\"Host\",\"accountHash\":42,\"badges\":[\"developer\"]}", Member.class);
		assertEquals("Host", m.getName());
		assertEquals(42L, m.getAccountHash());
		assertNull(m.getBadges());
	}

	@Test
	void legacyBareStringMemberStillParses() throws Exception {
		Member m = mapper.readValue("\"Alice\"", Member.class);
		assertEquals("Alice", m.getName());
		assertNull(m.getBadges());
	}

	@Test
	void badgesSerializeOnlyWhenPresent() throws Exception {
		String without = mapper.writeValueAsString(new Member("Host", 42L));
		assertFalse(without.contains("badges"));

		String with = mapper.writeValueAsString(new Member("Host", 42L, List.of("developer", "backer")));
		assertTrue(with.contains("\"badges\":[\"developer\",\"backer\"]"));
	}

	@Test
	void badgeDifferencesDriveMemberDeltas() {
		Member plain = new Member("Host", 42L);
		Member badged = new Member("Host", 42L, List.of("developer"));
		assertNotEquals(plain, badged);
		assertEquals(new Member("Host", 42L, List.of("developer")), badged);

		Party prev = new Party();
		prev.setId("p1");
		prev.setActivity("cox");
		prev.setMembers(List.of(plain));
		Party cur = Party.copyOf(prev);
		cur.setMembers(List.of(badged));

		PartyDelta delta = PartyDelta.diff(prev, cur);
		assertEquals(List.of(badged), delta.members());
	}
}

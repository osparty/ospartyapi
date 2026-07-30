package net.osparty.api.v2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The live party's beans resolve into a working graph: the manager takes the bus, the bus calls back into
 * the manager, and the {@code test} profile supplies the single-node halves of both.
 *
 * <p>Worth its own test because the wiring is circular and nothing else asserts it directly — a manager
 * that never registered itself on the bus would pass every other test in this package and then quietly
 * ignore every cross-node signal in production.
 */
@SpringBootTest
@ActiveProfiles("test")
// Ephemeral socket port: the server binds on context start, and 8081 may well be taken.
@TestPropertySource(properties = "app.party-v2.port=0")
class PartyV2ContextTest {
	@Autowired
	private PartyV2Manager manager;

	@Autowired
	private PartyV2Bus bus;

	@Test
	void theLivePartyBeansWireTogether() {
		assertThat(manager).isNotNull();
		assertThat(bus).isInstanceOf(LocalPartyV2Bus.class);
		// The manager registers itself on construction; without it no cross-node signal would land.
		assertThat(((LocalPartyV2Bus) bus).listener()).isNotNull();
	}
}

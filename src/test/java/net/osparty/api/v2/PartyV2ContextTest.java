package net.osparty.api.v2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole of V2 hangs off {@code app.party-v2.enabled}, so with the flag off — which is every other test
 * — none of these beans are ever built. This boots the context with it on, which is the only thing that
 * checks the wiring actually resolves: the manager takes the bus, the bus calls back into the manager, and
 * the {@code test} profile supplies the single-node halves of both.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.party-v2.enabled=true")
class PartyV2ContextTest {
	@Autowired
	private PartyV2Manager manager;

	@Autowired
	private PartyV2Bus bus;

	@Test
	void v2BeansWireTogetherWhenEnabled() {
		assertThat(manager).isNotNull();
		assertThat(bus).isInstanceOf(LocalPartyV2Bus.class);
		// The manager registers itself on construction; without it no cross-node signal would land.
		assertThat(((LocalPartyV2Bus) bus).listener()).isNotNull();
	}
}

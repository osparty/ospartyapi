package net.osparty.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The path matters more than the answer here: plugin 1.0.50 reads any failure to reach
 * {@code /api/v1/capabilities} as "this server predates the live party" and falls back to endpoints this
 * branch no longer serves, so a typo in the mapping strands it exactly as deleting the controller would.
 *
 * <p>Goes with {@link CapabilitiesController}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.socket.port=0")
class CapabilitiesControllerTest {
	@Autowired
	private TestRestTemplate rest;

	@Test
	void reportsBothSocketsOnThePath1050Probes() {
		ResponseEntity<String> response = rest.getForEntity("/api/v1/capabilities", String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).contains("\"partyV2\":true", "\"mergedSocket\":true");
	}

	/**
	 * The same, doubled-slash, which is what 1.0.50 sends when its base URL was entered with a trailing one.
	 *
	 * <p>It concatenates for this URL and uses {@code HttpUrl.Builder} for the socket, so that trailing slash
	 * reaches here and nowhere else: the socket still connects and only the probe fails. A 404 sends the
	 * client to the board-only endpoint and RuneLite's relay for the rest of the session, where no client on
	 * the merged socket can see it — silently, and looking for all the world like a working connection.
	 */
	@Test
	void answersTheDoubledSlashATrailingSlashInTheBaseUrlProduces() {
		ResponseEntity<String> response = rest.getForEntity("//api/v1/capabilities", String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).contains("\"partyV2\":true", "\"mergedSocket\":true");
	}
}

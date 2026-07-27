package net.osparty.api.v2.netty;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The two forms the live socket is served on, and nothing else. Netty's own path matching cannot express
 * this — one form is exact and the other has a variable segment in front — so the check is hand-written and
 * worth pinning down.
 */
class PartyV2PathFilterTest {
	@Test
	void acceptsBothFormsAndNothingElse() {
		assertThat(PartyV2PathFilter.accepts("/api/v2/ws/party")).isTrue();
		// A query string is not used, but a client that adds one must not be turned away for it.
		assertThat(PartyV2PathFilter.accepts("/api/v2/ws/party?v=1")).isTrue();
		assertThat(PartyV2PathFilter.accepts("/n/osparty-api-0/api/v2/ws/party")).isTrue();

		assertThat(PartyV2PathFilter.accepts("/n//api/v2/ws/party")).isFalse();
		// One segment of node id, not a path of them.
		assertThat(PartyV2PathFilter.accepts("/n/a/b/api/v2/ws/party")).isFalse();
		assertThat(PartyV2PathFilter.accepts("/api/v2/ws/party/extra")).isFalse();
		assertThat(PartyV2PathFilter.accepts("/api/v1/ws/parties")).isFalse();
		assertThat(PartyV2PathFilter.accepts("/")).isFalse();
		assertThat(PartyV2PathFilter.accepts(null)).isFalse();
	}
}

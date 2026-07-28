package net.osparty.api.v2.netty;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The endpoints this server serves and the protocols each one carries. Netty's own path matching cannot
 * express this — every endpoint has an optional variable segment in front of it — so the check is
 * hand-written and worth pinning down.
 */
class PartyV2PathFilterTest {
	@Test
	void eachEndpointNamesTheProtocolsItCarries() {
		assertThat(PartyV2PathFilter.route("/api/v2/ws/party")).isEqualTo(PartyV2PathFilter.Route.LIVE);
		// A query string is not used, but a client that adds one must not be turned away for it.
		assertThat(PartyV2PathFilter.route("/api/v2/ws/party?v=1")).isEqualTo(PartyV2PathFilter.Route.LIVE);
		// The ad board is served here too, so one server carries both protocols.
		assertThat(PartyV2PathFilter.route("/api/v1/ws/parties")).isEqualTo(PartyV2PathFilter.Route.BOARD);
		// And both over one connection, which is the whole point of the merged endpoint.
		assertThat(PartyV2PathFilter.route("/api/ws")).isEqualTo(PartyV2PathFilter.Route.MUX);
	}

	@Test
	void theNodeHintPrefixIsAcceptedOnEveryEndpoint() {
		assertThat(PartyV2PathFilter.route("/n/osparty-api-0/api/v2/ws/party"))
			.isEqualTo(PartyV2PathFilter.Route.LIVE);
		// The merged connection carries the live party, so it is the form that has to honour a hint.
		assertThat(PartyV2PathFilter.route("/n/osparty-api-1/api/ws"))
			.isEqualTo(PartyV2PathFilter.Route.MUX);
	}

	@Test
	void anythingElseIsRefused() {
		assertThat(PartyV2PathFilter.accepts("/n//api/v2/ws/party")).isFalse();
		// One segment of node id, not a path of them.
		assertThat(PartyV2PathFilter.accepts("/n/a/b/api/v2/ws/party")).isFalse();
		assertThat(PartyV2PathFilter.accepts("/api/v2/ws/party/extra")).isFalse();
		assertThat(PartyV2PathFilter.accepts("/api/ws/extra")).isFalse();
		assertThat(PartyV2PathFilter.accepts("/")).isFalse();
		assertThat(PartyV2PathFilter.accepts(null)).isFalse();
	}
}

package net.osparty.api.party.netty;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The one endpoint this server serves, in both its forms. Netty's own path matching cannot express this —
 * the endpoint has an optional variable segment in front of it — so the check is hand-written and worth
 * pinning down.
 */
class SocketPathHandlerTest {
	@Test
	void bothFormsOfTheEndpointAreAccepted() {
		assertThat(SocketPathHandler.accepts("/api/ws")).isTrue();
		// A query string is not used, but a client that adds one must not be turned away for it.
		assertThat(SocketPathHandler.accepts("/api/ws?v=1")).isTrue();
		// The node hint pins the connection to the pod that owns the caller's party.
		assertThat(SocketPathHandler.accepts("/n/osparty-api-1/api/ws")).isTrue();
	}

	@Test
	void anythingElseIsRefused() {
		// The two single-protocol endpoints are gone: both protocols share one connection now.
		assertThat(SocketPathHandler.accepts("/api/v1/ws/parties")).isFalse();
		assertThat(SocketPathHandler.accepts("/api/v2/ws/party")).isFalse();
		assertThat(SocketPathHandler.accepts("/n/osparty-api-0/api/v2/ws/party")).isFalse();

		assertThat(SocketPathHandler.accepts("/n//api/ws")).isFalse();
		// One segment of node id, not a path of them.
		assertThat(SocketPathHandler.accepts("/n/a/b/api/ws")).isFalse();
		assertThat(SocketPathHandler.accepts("/api/ws/extra")).isFalse();
		assertThat(SocketPathHandler.accepts("/")).isFalse();
		assertThat(SocketPathHandler.accepts(null)).isFalse();
	}
}

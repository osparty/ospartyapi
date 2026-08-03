package net.osparty.api.party.netty;

import static org.assertj.core.api.Assertions.assertThat;

import net.osparty.api.party.netty.SocketPathHandler.Route;
import org.junit.jupiter.api.Test;

/**
 * The endpoints this server serves, in every form. Netty's own path matching cannot express this — the
 * merged endpoint has an optional variable segment in front of it — so the check is hand-written and worth
 * pinning down.
 */
class SocketPathHandlerTest {
	@Test
	void bothFormsOfTheMergedEndpointAreAccepted() {
		assertThat(SocketPathHandler.route("/api/ws")).isEqualTo(Route.MUX);
		// A query string is not used, but a client that adds one must not be turned away for it.
		assertThat(SocketPathHandler.route("/api/ws?v=1")).isEqualTo(Route.MUX);
		// The node hint pins the connection to the pod that owns the caller's party.
		assertThat(SocketPathHandler.route("/n/osparty-api-1/api/ws")).isEqualTo(Route.MUX);
	}

	/**
	 * Plugin 1.0.50 falls back here when the merged socket has failed it repeatedly, so a 404 turns a bad
	 * connection into a client with no discovery at all. Goes when that release does.
	 */
	@Test
	void theBoardsOwnEndpointIsStillServed() {
		assertThat(SocketPathHandler.route("/api/v1/ws/parties")).isEqualTo(Route.BOARD);
		assertThat(SocketPathHandler.route("/api/v1/ws/parties?v=1")).isEqualTo(Route.BOARD);
	}

	@Test
	void anythingElseIsRefused() {
		// The live party never had an endpoint a released plugin dialled on its own.
		assertThat(SocketPathHandler.route("/api/v2/ws/party")).isEqualTo(Route.NONE);
		assertThat(SocketPathHandler.route("/n/osparty-api-0/api/v2/ws/party")).isEqualTo(Route.NONE);

		assertThat(SocketPathHandler.accepts("/n//api/ws")).isFalse();
		// One segment of node id, not a path of them.
		assertThat(SocketPathHandler.accepts("/n/a/b/api/ws")).isFalse();
		assertThat(SocketPathHandler.accepts("/api/ws/extra")).isFalse();
		assertThat(SocketPathHandler.accepts("/api/v1/ws/parties/extra")).isFalse();
		assertThat(SocketPathHandler.accepts("/")).isFalse();
		assertThat(SocketPathHandler.accepts(null)).isFalse();
	}
}

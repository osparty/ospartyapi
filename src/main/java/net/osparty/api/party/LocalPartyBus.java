package net.osparty.api.party;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node control plane: there are no other nodes to signal, so publishing is a no-op. Used in tests
 * and any deployment without Redis, mirroring {@link net.osparty.api.web.ws.LocalInviteBus}.
 * {@link RedisPartyBus} is the multi-node implementation.
 *
 * <p>The listener is still held and still invokable, so a test can deliver a signal by hand and assert what
 * the node does with it.
 */
@Component
@Profile("test")
public class LocalPartyBus implements PartyBus {
	private volatile Listener listener;

	@Override
	public void publishOwnerChanged(String room, String nodeId) {
		// No peers to tell.
	}

	@Override
	public void publishForceReconnect(String room) {
		// No peers to tell.
	}

	@Override
	public void setListener(Listener listener) {
		this.listener = listener;
	}

	/** The registered listener, so a single-node test can deliver a signal as a peer node would. */
	Listener listener() {
		return listener;
	}
}

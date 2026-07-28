package net.osparty.api.web.ws;

import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node {@link PartyChangeBus}: a publish is delivered straight back to the listener, which is what
 * a one-node cluster would do anyway. Used by the tests, which have no Redis.
 */
@Component
@Profile("test")
public class LocalPartyChangeBus implements PartyChangeBus {
	private volatile Consumer<String> listener = id -> { };

	@Override
	public void setListener(Consumer<String> listener) {
		this.listener = listener;
	}

	@Override
	public void publish(String partyId) {
		if (partyId != null) {
			listener.accept(partyId);
		}
	}

	Consumer<String> listener() {
		return listener;
	}
}

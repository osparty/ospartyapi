package net.osparty.api.web.ws;

import java.util.function.BiConsumer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node {@link BoardChangeBus}: a publish is delivered straight back to the listener, which is what
 * a one-node cluster would do anyway. Used by the tests, which have no Redis.
 */
@Component
@Profile("test")
public class LocalBoardChangeBus implements BoardChangeBus {
	private volatile BiConsumer<String, Long> listener = (id, seq) -> { };

	@Override
	public void setListener(BiConsumer<String, Long> listener) {
		this.listener = listener;
	}

	@Override
	public void publish(String adId, long seq) {
		if (adId != null) {
			listener.accept(adId, seq);
		}
	}

	BiConsumer<String, Long> listener() {
		return listener;
	}
}

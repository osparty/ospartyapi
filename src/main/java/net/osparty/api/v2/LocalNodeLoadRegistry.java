package net.osparty.api.v2;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node load: there is nowhere else to put a party, so every room is hosted locally. Used in tests and
 * any deployment without Redis, mirroring {@link LocalPartyOwnershipService}.
 */
@Component
@Profile("test")
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class LocalNodeLoadRegistry implements NodeLoadRegistry {
	@Override
	public void publish(int members) {
		// Nothing to publish to.
	}

	@Override
	public void retire() {
		// Nothing to withdraw from.
	}

	@Override
	public Optional<String> preferredHost(int selfMembers) {
		return Optional.empty();
	}
}

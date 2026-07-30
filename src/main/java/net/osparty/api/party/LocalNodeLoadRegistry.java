package net.osparty.api.party;

import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Single-node load: there is nowhere else to put a party, so every room is hosted locally. Used in tests and
 * any deployment without Redis, mirroring {@link LocalPartyOwnershipService}.
 */
@Component
@Profile("test")
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

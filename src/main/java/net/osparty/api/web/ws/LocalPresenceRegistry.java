package net.osparty.api.web.ws;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class LocalPresenceRegistry implements PresenceRegistry {
	@Override
	public int record(int localCount) {
		return localCount;
	}
}

package net.osparty.api.web;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment can do, so a client can decide before it opens anything.
 *
 * <p>A plugin release outlives any single server configuration: users update on their own schedule and some
 * never do, so a client has to work against a deployment that has the live party turned on and against one
 * that has not. Asking is cheaper and less ambiguous than trying the newer endpoint and reading a 404 —
 * a 404 could equally be a proxy, a typo, or a deployment mid-roll.
 *
 * <p>It also makes the live party a switch the server owns. Turning it off is a config change here rather
 * than a plugin release, because clients that asked and were told no simply keep using the endpoints they
 * always used. That is what makes the migration reversible.
 *
 * <p>Deliberately unauthenticated and free of anything about a caller: it reports which sockets exist, and
 * every one of those is already discoverable by connecting to it.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilitiesController {
	private final boolean partyV2;
	private final boolean mergedSocket;

	public CapabilitiesController(
		@Value("${app.party-v2.enabled:false}") boolean partyV2,
		@Value("${app.party-v2.transport:tomcat}") String transport) {
		this.partyV2 = partyV2;
		// The merged endpoint lives on the Netty server, so it exists exactly when that transport does.
		this.mergedSocket = partyV2 && "netty".equalsIgnoreCase(transport);
	}

	/**
	 * @return {@code partyV2} — the live party is served at all; {@code mergedSocket} — both protocols can
	 *     be carried on one connection at {@code /api/ws}, so a client need not hold two.
	 */
	@GetMapping
	public Map<String, Boolean> capabilities() {
		return Map.of("partyV2", partyV2, "mergedSocket", mergedSocket);
	}
}

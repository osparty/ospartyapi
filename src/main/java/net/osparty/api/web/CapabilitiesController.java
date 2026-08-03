package net.osparty.api.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment can do, asked by a plugin before it opens anything.
 *
 * <p>Kept for plugin 1.0.50, which is what the hub serves while 1.0.51 waits on RuneLite's review. That
 * client probes here at startup and reads a failure as "this server is older than the live party" — so a
 * 404 sends it to {@code /api/v1/ws/parties}, which this branch no longer serves, and it ends up with no
 * board and no live party at all.
 *
 * <p>Both answers are now constants. The live party is the only thing served, on the only socket there is,
 * so there is nothing left for a client to choose between; the endpoint exists to say so rather than to
 * report a configuration. Delete it, and {@code privateParty}/{@code parties}/{@code party} in
 * {@link net.osparty.api.model.Advertisement} and {@code BoardBroadcaster.Outbound}, once
 * {@code osparty.ws.connections} shows nobody on 1.0.50.
 *
 * <p>Unauthenticated and free of anything about a caller, as before: it reports which sockets exist, and
 * every one of those is already discoverable by connecting to it.
 *
 * <p>Mapped twice, because 1.0.50 builds this one URL by concatenation — {@code apiBaseUrl() + "/api/v1/
 * capabilities"} — while it builds its socket URL through {@code HttpUrl.Builder}, which collapses a
 * doubled slash. A base URL entered with a trailing slash therefore leaves the socket reachable and only
 * the probe 404, which is the worst shape this failure could take: the client concludes the server predates
 * the live party, drops to the board-only endpoint and RuneLite's relay, and goes invisible to every client
 * on the merged socket. It never says so, and nothing it does afterwards looks broken. Answering the URL it
 * actually sends is cheaper than the support that alternative costs.
 */
@RestController
public class CapabilitiesController {
	/**
	 * @return {@code partyV2} — the live party is served at all; {@code mergedSocket} — both protocols are
	 *     carried on one connection at {@code /api/ws}, so a client need not hold two.
	 */
	@GetMapping({ "/api/v1/capabilities", "//api/v1/capabilities" })
	public Map<String, Boolean> capabilities() {
		return Map.of("partyV2", true, "mergedSocket", true);
	}
}

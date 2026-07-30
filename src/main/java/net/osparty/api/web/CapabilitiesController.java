package net.osparty.api.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment can do, so a client can decide before it opens anything.
 *
 * <p>Both answers are now constant — the live party is the only live party, and it is always on the merged
 * socket. <b>Keep this endpoint anyway.</b> A released client asks once at startup and falls back to
 * {@code /api/v1/ws/parties} when it cannot get an answer, so this is precisely what moves the installed
 * base onto {@code /api/ws} and lets that fallback endpoint be retired. Removing it would pin every client
 * that has not updated to the path being removed, silently — they would simply stop connecting.
 *
 * <p>Deliberately unauthenticated and free of anything about a caller: it reports which sockets exist, and
 * every one of those is already discoverable by connecting to it.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilitiesController {
	/**
	 * @return {@code partyV2} — the live party is served; {@code mergedSocket} — both protocols are carried
	 *     on one connection at {@code /api/ws}, so a client need not hold two.
	 */
	@GetMapping
	public Map<String, Boolean> capabilities() {
		return Map.of("partyV2", true, "mergedSocket", true);
	}
}

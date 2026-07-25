package net.osparty.api.v2;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Join-path owner resolver (PARTY_V2_MIGRATION.md §9/§17.3): a joiner asks which node owns a room, then
 * opens one WebSocket straight to {@code /n/{nodeId}/api/v2/ws/party} — skipping the throwaway-handshake
 * that a {@code redirect} would cost. {@code redirect} stays the correctness backstop for the failover race.
 *
 * <p>Gated by {@code app.party-v2.enabled}; reads the same {@link PartyOwnershipService#lookup} the handler
 * uses, so there is no separate ownership source to drift.
 */
@RestController
@RequestMapping("/api/v2/party")
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2OwnerController {
	private final PartyOwnershipService ownership;

	public PartyV2OwnerController(PartyOwnershipService ownership) {
		this.ownership = ownership;
	}

	/** {@code {nodeId}} of the room's owner, or 404 if no node currently owns it. */
	@GetMapping("/{code}/owner")
	public ResponseEntity<Map<String, String>> owner(@PathVariable String code) {
		return ownership.lookup(code)
			.<ResponseEntity<Map<String, String>>>map(o -> ResponseEntity.ok(Map.of("nodeId", o.nodeId())))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}
}

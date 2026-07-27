package net.osparty.api.web;

import net.osparty.api.service.DiscordLinkImporter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator escape hatches, guarded by {@code InternalTokenFilter} via the {@code /internal/} prefix.
 *
 * <p>Both routes here are also run automatically at startup; they exist so an operator can trigger
 * and observe them on demand -- verifying an import landed, or re-warming the mirror after a Redis
 * flush -- without restarting a replica.
 */
@RestController
@RequestMapping("/internal/admin")
public class InternalAdminController {
	private final DiscordLinkImporter importer;

	public InternalAdminController(DiscordLinkImporter importer) {
		this.importer = importer;
	}

	/** Replays the pre-cutover Redis links into Postgres. Idempotent; a no-op once completed. */
	@PostMapping("/import-discord-links")
	public ResponseEntity<DiscordLinkImporter.Result> importDiscordLinks() {
		return ResponseEntity.ok(importer.importOnce());
	}

	/** Repopulates the Redis mirror the broadcast path reads from Postgres. */
	@PostMapping("/warm-discord-links")
	public ResponseEntity<WarmResult> warmDiscordLinks() {
		return ResponseEntity.ok(new WarmResult(importer.warmMirror()));
	}

	public record WarmResult(int links) {
	}
}

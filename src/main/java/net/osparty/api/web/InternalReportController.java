package net.osparty.api.web;

import net.osparty.api.model.AdReport;
import net.osparty.api.repository.AdReportRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review outcomes that are not bans. Guarded by {@code InternalTokenFilter} via the
 * {@code /internal/} prefix; banning lives in {@link InternalBanController}.
 */
@RestController
@RequestMapping("/internal/reports")
public class InternalReportController {
	private final AdReportRepository reports;

	public InternalReportController(AdReportRepository reports) {
		this.reports = reports;
	}

	/** Marks a report reviewed with no action taken, so it stops looking unattended. */
	@PostMapping("/{id}/dismiss")
	public ResponseEntity<Void> dismiss(@PathVariable long id, @RequestBody DismissRequest request) {
		if (request == null || request.moderatorDiscordId() == null) {
			return ResponseEntity.badRequest().build();
		}
		boolean updated = reports.markReviewed(id, AdReport.STATUS_DISMISSED,
			request.moderatorDiscordId(), request.moderatorDiscordName(), null);
		// 404 lets the bot tell the moderator the report aged out of retention rather than
		// silently pretending the click did something.
		return updated ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	public record DismissRequest(String moderatorDiscordId, String moderatorDiscordName) {
	}
}

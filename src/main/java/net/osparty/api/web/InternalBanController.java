package net.osparty.api.web;

import java.util.List;
import net.osparty.api.model.AdBan;
import net.osparty.api.model.AdReport;
import net.osparty.api.repository.AdReportRepository;
import net.osparty.api.service.BanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ban and unban, invoked by the Discord bot when a moderator clicks a review button.
 *
 * <p>Guarded, like every {@code /internal/} route, by {@code InternalTokenFilter} on the shared
 * service token. There is no per-moderator authentication here: the bot has already checked that
 * the clicker holds the admin role, and the moderator's identity is recorded for the audit trail
 * rather than trusted for authorisation.
 *
 * <h2>Why the request carries only a report id</h2>
 * A Discord button lives forever and can be clicked long after the advertisement expired, so it
 * cannot carry the subject itself -- that would be a second, forgeable, possibly stale copy of who
 * is being banned. The subject is resolved here from the immutable {@code ad_report} row, which is
 * the actual record of what was reported. A direct-subject form is offered alongside it so an
 * operator can ban by hand with curl before any of the Discord plumbing exists.
 */
@RestController
@RequestMapping("/internal/bans")
public class InternalBanController {
	private static final Logger log = LoggerFactory.getLogger(InternalBanController.class);

	private final BanService bans;
	private final AdReportRepository reports;

	public InternalBanController(BanService bans, AdReportRepository reports) {
		this.bans = bans;
		this.reports = reports;
	}

	@PostMapping
	public ResponseEntity<BanResponse> ban(@RequestBody BanRequest request) {
		if (request == null || request.moderatorDiscordId() == null) {
			return ResponseEntity.badRequest().build();
		}
		Subject subject = resolve(request.reportId(), request.host(), request.accountHash());
		if (subject == null) {
			return ResponseEntity.notFound().build();
		}
		AdBan ban = bans.ban(subject.host(), subject.accountHash(), request.reason(),
			request.moderatorDiscordId(), request.moderatorDiscordName(), request.reportId());
		if (request.reportId() != null) {
			reports.markReviewed(request.reportId(), AdReport.STATUS_BANNED,
				request.moderatorDiscordId(), request.moderatorDiscordName(), ban.getId());
		}
		return ResponseEntity.ok(BanResponse.of(ban, subject));
	}

	@PostMapping("/unban")
	public ResponseEntity<BanResponse> unban(@RequestBody BanRequest request) {
		if (request == null || request.moderatorDiscordId() == null) {
			return ResponseEntity.badRequest().build();
		}
		Subject subject = resolve(request.reportId(), request.host(), request.accountHash());
		if (subject == null) {
			return ResponseEntity.notFound().build();
		}
		List<AdBan> revoked = bans.unban(subject.host(), subject.accountHash(), request.reason(),
			request.moderatorDiscordId(), request.moderatorDiscordName());
		if (revoked.isEmpty()) {
			// Already unbanned. Idempotent on purpose: two moderators clicking, or a stale Discord
			// message being clicked twice, should both read as success rather than as an error.
			log.info("Unban for host='{}' accountHash={} matched no active ban",
				subject.host(), subject.accountHash());
			return ResponseEntity.ok(new BanResponse(null, subject.host(), subject.accountHash(), false));
		}
		return ResponseEntity.ok(BanResponse.of(revoked.get(0), subject));
	}

	/**
	 * Resolves who is being banned: from the report row when a report id is supplied, otherwise
	 * from the explicit fields. Returns null when a report id was given but no such report exists,
	 * which is how a click on a message older than the report retention window reports itself.
	 */
	private Subject resolve(Long reportId, String host, Long accountHash) {
		if (reportId != null) {
			AdReport report = reports.findById(reportId).orElse(null);
			if (report == null) {
				return null;
			}
			return new Subject(
				report.getHostNameRaw() != null ? report.getHostNameRaw() : report.getHostName(),
				report.getHostAccountHash() == null ? 0 : report.getHostAccountHash());
		}
		if (host == null || host.isBlank()) {
			return accountHash == null ? null : new Subject("", accountHash);
		}
		return new Subject(host, accountHash == null ? 0 : accountHash);
	}

	private record Subject(String host, long accountHash) {
	}

	/**
	 * @param reportId resolve the subject from this report; omit to use {@code host}/{@code accountHash}
	 */
	public record BanRequest(Long reportId, String host, Long accountHash, String reason,
		String moderatorDiscordId, String moderatorDiscordName) {
	}

	/** Echoes the resolved subject so the bot can render it without carrying it in the button. */
	public record BanResponse(Long banId, String host, long accountHash, boolean active) {
		static BanResponse of(AdBan ban, Subject subject) {
			return new BanResponse(ban.getId(), subject.host(), subject.accountHash(), ban.isActive());
		}
	}
}

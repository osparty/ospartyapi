package net.osparty.api.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.osparty.api.model.AdBan;
import net.osparty.api.model.Party;
import net.osparty.api.repository.BanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Decides whether an advertisement is hidden from the board, and applies moderator ban/unban writes.
 *
 * <h2>Why a snapshot</h2>
 * The reconciler evaluates every live party every few seconds on every replica, so the hidden check
 * has to be an in-memory set lookup. This service reloads that set on a fixed interval; a ban
 * therefore takes effect cluster-wide within one refresh plus one reconcile tick, with no
 * inter-node messaging at all. Each replica independently reads Postgres and independently
 * reconciles against shared Redis, exactly like the existing ad fan-out.
 *
 * <h2>Fail-open, deliberately</h2>
 * If the reload throws, the previous snapshot is kept rather than cleared. The failure mode of a
 * stale ban list is "a spammer stays visible a while longer"; the failure mode of an empty-on-error
 * list would be the same, but the failure mode of erroring toward "everything hidden" would be a
 * blank bulletin board for every user during a database blip. Never let moderation tooling take
 * down the product it moderates.
 */
@Service
public class BanService {
	private static final Logger log = LoggerFactory.getLogger(BanService.class);

	private final BanRepository repository;
	private volatile Snapshot snapshot = Snapshot.EMPTY;

	public BanService(BanRepository repository, MeterRegistry meterRegistry) {
		this.repository = repository;
		Gauge.builder("osparty.bans.active", this, service -> service.snapshot.size())
			.description("Ad-board hosts currently shadowbanned")
			.register(meterRegistry);
		Gauge.builder("osparty.bans.snapshot.age.seconds", this,
				service -> (System.currentTimeMillis() - service.snapshot.loadedAtMs()) / 1000.0)
			.description("Age of this node's cached ban snapshot")
			.register(meterRegistry);
	}

	/**
	 * Whether this advertisement is hidden from everyone but its own host.
	 *
	 * <p>Only the host is ever checked. Scanning the whole member list would mean a banned player
	 * joining an innocent host's party silently shadowbans that innocent host -- a griefing vector
	 * dressed up as moderation.
	 */
	public boolean isHidden(Party party) {
		if (party == null) {
			return false;
		}
		return isBanned(party.getHost(), party.getHostAccountHash());
	}

	/** Whether this subject is banned by name or by account hash. Either match is enough. */
	public boolean isBanned(String host, long accountHash) {
		Snapshot current = snapshot;
		if (current.isEmpty()) {
			return false;
		}
		if (accountHash != 0 && current.hashes().contains(accountHash)) {
			return true;
		}
		return host != null && current.names().contains(PartyFactory.normalizeHost(host));
	}

	public AdBan ban(String host, long accountHash, String reason, String moderatorDiscordId,
		String moderatorDiscordName, Long sourceReportId) {
		String normalized = host == null ? "" : PartyFactory.normalizeHost(host);
		AdBan ban = repository.ban(normalized, host, accountHash == 0 ? null : accountHash, reason,
			moderatorDiscordId, moderatorDiscordName, sourceReportId);
		log.info("Ad ban applied: id={} host='{}' accountHash={} by={} report={}",
			ban.getId(), normalized, accountHash, moderatorDiscordName, sourceReportId);
		refresh();
		return ban;
	}

	public List<AdBan> unban(String host, long accountHash, String reason, String moderatorDiscordId,
		String moderatorDiscordName) {
		String normalized = host == null ? "" : PartyFactory.normalizeHost(host);
		List<AdBan> revoked = repository.revoke(normalized, accountHash == 0 ? null : accountHash,
			moderatorDiscordId, moderatorDiscordName, reason);
		log.info("Ad ban revoked: host='{}' accountHash={} rows={} by={}",
			normalized, accountHash, revoked.size(), moderatorDiscordName);
		refresh();
		return revoked;
	}

	/**
	 * Reloads the snapshot. Also invoked directly after a write so the moderator who clicked sees
	 * the effect on their own node immediately instead of waiting out the interval.
	 */
	@Scheduled(fixedDelayString = "${app.bans.refresh-ms:5000}")
	public void refresh() {
		try {
			List<BanRepository.ActiveBan> active = repository.findActive();
			Set<String> names = new HashSet<>();
			Set<Long> hashes = new HashSet<>();
			for (BanRepository.ActiveBan ban : active) {
				if (ban.hostName() != null && !ban.hostName().isEmpty()) {
					names.add(ban.hostName());
				}
				if (ban.accountHash() != null) {
					hashes.add(ban.accountHash());
				}
			}
			snapshot = new Snapshot(Set.copyOf(names), Set.copyOf(hashes), System.currentTimeMillis());
		}
		catch (Exception e) {
			// Keep serving the previous snapshot. See the class javadoc: erroring toward "hide
			// everything" would blank the board for every user.
			log.warn("Ban snapshot refresh failed; keeping the previous {} entr(ies): {}",
				snapshot.size(), e.toString());
		}
	}

	private record Snapshot(Set<String> names, Set<Long> hashes, long loadedAtMs) {
		static final Snapshot EMPTY = new Snapshot(Set.of(), Set.of(), System.currentTimeMillis());

		boolean isEmpty() {
			return names.isEmpty() && hashes.isEmpty();
		}

		int size() {
			return names.size() + hashes.size();
		}
	}
}

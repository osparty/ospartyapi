package net.osparty.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stored per-install credentials. One row per (account, machine); several per account is normal.
 *
 * <p>Only {@code tokenHash} is held -- the SHA-256 of the token we issued. The token itself lives on the
 * client that owns it and nowhere else, so this store is not a set of working credentials even to us.
 */
public interface AccountCredentialRepository {
	/** A credential as stored: which account it speaks for, and whether it still may. */
	record Credential(String tokenHash, long accountHash, String label, Instant issuedAt, Instant lastSeenAt,
		Instant revokedAt) {

		public boolean active() {
			return revokedAt == null;
		}
	}

	void insert(String tokenHash, long accountHash, String label);

	/** The credential behind a presented token, revoked ones included -- the caller decides what that means. */
	Optional<Credential> findByTokenHash(String tokenHash);

	/** Every live credential for an account: the machines currently entitled to speak for it. */
	List<Credential> findActiveByAccountHash(long accountHash);

	/** How many live credentials an account has. Cheaper than listing them, which is all enrolment needs. */
	int countActiveByAccountHash(long accountHash);

	/** Note that a credential was used, so a device list can show something more useful than "issued". */
	void touch(String tokenHash);

	/** Withdraw one credential. The account keeps its others; this is "sign out that machine". */
	void revoke(String tokenHash);

	/** Withdraw every credential for an account, for when the answer is "none of these are mine". */
	int revokeAllForAccount(long accountHash);

	/** Record that an enrolment happened. Outlives the credential it describes; see the changelog. */
	void logEnrolment(long accountHash, String clientIp, boolean firstDevice);
}

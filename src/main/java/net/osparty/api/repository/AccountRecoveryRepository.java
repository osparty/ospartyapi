package net.osparty.api.repository;

import java.util.List;
import java.util.Optional;

/**
 * One-time codes that let a machine enrol on an account with nothing else to prove itself against.
 *
 * <p>Only the SHA-256 of each code is held, as with {@link AccountCredentialRepository} -- the codes
 * themselves exist on whatever the user wrote them down on and in the one response that issued them.
 *
 * <p>The single-use guarantee lives in {@link #markUsed}, not in the caller. Two connections redeeming the
 * same code at the same moment both find it unused, so a check-then-write in a service would let both
 * through; the conditional update is what makes the second one lose.
 */
public interface AccountRecoveryRepository {
	/** A code as stored. Kept after redemption: which one was spent and when is the only trail there is. */
	record RecoveryCode(String codeHash, long accountHash, java.time.Instant issuedAt,
		java.time.Instant usedAt, String usedIp) {

		public boolean unused() {
			return usedAt == null;
		}
	}

	/**
	 * Replace an account's unused codes with a fresh set.
	 *
	 * <p>Issuing always replaces rather than adds, so "generate new codes" means the old sheet stops
	 * working -- which is what a user who has just decided their old codes are compromised expects it to
	 * mean. Spent codes are left alone; they are history, not credentials.
	 */
	void replaceUnused(long accountHash, List<String> codeHashes);

	/** The code behind a presented value, spent ones included -- the caller decides what that means. */
	Optional<RecoveryCode> findByCodeHash(String codeHash);

	/**
	 * Spend a code, if it is still unused.
	 *
	 * @return true when this call is the one that spent it; false when it was already used or unknown.
	 */
	boolean markUsed(String codeHash, String clientIp);

	/** How many of an account's codes are still good. The only number the device list needs. */
	int countUnused(long accountHash);
}

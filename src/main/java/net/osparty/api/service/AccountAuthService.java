package net.osparty.api.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.osparty.api.repository.AccountCredentialRepository;
import net.osparty.api.repository.AccountCredentialRepository.Credential;
import net.osparty.api.repository.AccountRecoveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and checks the credential a client presents to prove which account it is.
 *
 * <p><b>Trust on first use, and what that does and does not buy.</b> Nothing RuneLite exposes can prove an
 * account belongs to the person at the keyboard -- the plugin hub forbids the one API that came close, and
 * Jagex publishes nothing a third party can verify. So the first client to present an account hash is
 * believed, and is given a credential; from then on the credential is the identity and the hash in a frame is
 * ignored. That stops impersonation, ban evasion by hash-swap and invite hijacking permanently, for every
 * account that has ever connected. It does not prove ownership at the moment of enrolment.
 *
 * <p>That trade is only acceptable while account hashes are not discoverable. They used to ride in the live
 * payload relayed to anyone who knocked on a public party, which made enrolment open season -- see
 * WEBSOCKET_AUTH_RESEARCH.md §10.1. Enrolment must not be switched on before that is closed.
 *
 * <p><b>Why several credentials per account.</b> One person plays from a desktop and a laptop, and a
 * credential is per install because that is where it is stored. The account is the identity; credentials are
 * the machines allowed to speak for it. A new machine on a known account is the ordinary case, not an
 * intrusion, so it enrols -- and every enrolment is recorded, because that record is the only thing that
 * makes a hijack visible after the fact.
 */
@Service
public class AccountAuthService {
	private static final Logger log = LoggerFactory.getLogger(AccountAuthService.class);

	/** 256 bits, base64url, no padding. Long enough that guessing is not a strategy; short enough for a header. */
	private static final int TOKEN_BYTES = 32;

	/**
	 * A ceiling on machines per account. Not a security boundary -- someone who can enrol once can enrol
	 * again -- but an unbounded set is a leak that never stops growing, and a real person does not have
	 * thirty computers. Hitting it means something is wrong and worth a log line.
	 */
	private static final int MAX_DEVICES = 20;

	/**
	 * How many recovery codes an account gets.
	 *
	 * <p>Enough that losing a few to a misread handwritten sheet does not matter, few enough to fit on one.
	 */
	private static final int RECOVERY_CODE_COUNT = 10;

	/**
	 * Characters and length of a recovery code: Crockford's base32 alphabet, which drops I, L, O and U so
	 * nothing in it can be misread as something else in it. Sixteen of them is eighty bits -- unlike the
	 * six-digit coupling code, this one has no expiry and no second machine standing behind it, so guessing
	 * has to be hopeless on its own rather than merely slow.
	 */
	private static final String RECOVERY_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
	private static final int RECOVERY_CODE_LENGTH = 16;
	private static final int RECOVERY_GROUP = 4;

	private final AccountCredentialRepository store;
	private final AccountRecoveryRepository recovery;
	private final CouplingCodeStore couplings;
	private final SecureRandom random = new SecureRandom();
	private final boolean enrolmentEnabled;

	public AccountAuthService(AccountCredentialRepository store, AccountRecoveryRepository recovery,
		CouplingCodeStore couplings,
		// Enrolment stays off until raw account hashes have stopped leaving the server (research doc
		// §10.1). Verification of already-issued credentials is always on: it can only ever refuse.
		@Value("${app.auth.enrolment-enabled:true}") boolean enrolmentEnabled) {
		this.store = store;
		this.recovery = recovery;
		this.couplings = couplings;
		this.enrolmentEnabled = enrolmentEnabled;
	}

	/**
	 * A freshly issued credential. The plaintext exists here and in the response, then never again.
	 *
	 * @param recoveryCodes minted alongside the account's first credential and delivered with it, or empty
	 *     on every later enrolment. The one moment an account has exactly one way back in is the moment it
	 *     has exactly one device, so that is when a second one is worth pressing on the user -- asking later
	 *     means asking someone who has not yet had the problem, and being told no.
	 */
	public record Issued(String token, long accountHash, boolean firstDevice, List<String> recoveryCodes) {
	}

	public boolean enrolmentEnabled() {
		return enrolmentEnabled;
	}

	/**
	 * Resolve a presented token to the account it speaks for, or empty when it is unknown or withdrawn.
	 *
	 * <p>Failing closed on anything unrecognised is the point: an unresolvable token is not an error the
	 * caller should route around, it is a connection that has not proved anything and must be treated as
	 * unauthenticated.
	 */
	public Optional<Long> accountFor(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		Optional<Credential> found = store.findByTokenHash(hash(token));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Credential credential = found.get();
		if (!credential.active()) {
			log.info("Rejected a revoked credential for account {}", credential.accountHash());
			return Optional.empty();
		}
		return Optional.of(credential.accountHash());
	}

	/** Note that a credential was used, so the device list shows when rather than only that. */
	public void touch(String token) {
		if (token != null && !token.isBlank()) {
			store.touch(hash(token));
		}
	}

	/**
	 * Issue a credential binding {@code accountHash} to the machine that asked, or empty when enrolment is
	 * off or the account already has more machines than makes sense.
	 *
	 * @param label a client-reported name for the device (e.g. its hostname), shown back in the device list
	 *     so "which one is this" has an answer better than a bare timestamp. Best-effort and unverified --
	 *     purely a display convenience, never used to decide anything. Null when the client sent none.
	 */
	public Optional<Issued> enrol(long accountHash, String clientIp, String label) {
		if (!enrolmentEnabled) {
			return Optional.empty();
		}
		if (accountHash == 0 || accountHash == -1) {
			// No account to bind to. Both spellings of "logged out" are folded at decode, but a caller
			// reaching here with one has skipped that and must not enrol every logged-out client as one
			// shared identity.
			return Optional.empty();
		}
		// First machine only. Anything after that has to prove itself by coupling, and the check lives here
		// as well as in the caller: a path that reached this method without asking would otherwise hand out
		// a credential for an account somebody else already holds, which is the whole thing coupling exists
		// to prevent.
		int existing = store.countActiveByAccountHash(accountHash);
		if (existing > 0) {
			log.warn("Refusing plain enrolment for account {}: it already has {} devices, so this must couple",
				accountHash, existing);
			return Optional.empty();
		}
		String token = mintToken();
		store.insert(hash(token), accountHash, sanitizeLabel(label));
		store.logEnrolment(accountHash, clientIp, true);
		// Minted here rather than waiting to be asked. An account with one device has one way back in, and
		// the machine holding it is the machine that will one day be the problem -- so the codes have to
		// exist before that day, and this is the only moment we are certain to have the user's attention.
		List<String> codes = mintRecoveryCodes(accountHash);
		log.info("Enrolled the first device for account {} with {} recovery codes", accountHash, codes.size());
		return Optional.of(new Issued(token, accountHash, true, codes));
	}

	/** The machines currently entitled to speak for an account. */
	public List<Credential> devices(long accountHash) {
		return store.findActiveByAccountHash(accountHash);
	}

	/** Withdraw one machine's credential; the account keeps its others. Only that machine can call this. */
	public void revoke(String token) {
		if (token != null && !token.isBlank()) {
			store.revoke(hash(token));
		}
	}

	/**
	 * Withdraw one machine's credential by device id, from a <em>different</em> machine on the same
	 * account. This is the only way a lost or stolen device is ever removed: {@link #revoke} needs the
	 * plaintext token, which by then only the lost device still holds.
	 *
	 * <p>{@code deviceId} is the token's stored digest -- see {@link #devices}, which is the only place a
	 * caller can have gotten one from. Knowing a digest cannot authenticate as the device (the handshake
	 * presents the plaintext, which the server hashes and compares; the digest alone opens nothing), so
	 * handing it back to the client that owns the account is safe.
	 *
	 * <p>The ownership check is what makes this safe to expose to any authenticated caller: without it,
	 * knowing (or guessing) any digest would let a session revoke a device on an account that isn't its
	 * own. {@link net.osparty.api.web.ws.BoardBroadcaster} only ever calls this with the caller's own
	 * authenticated {@code accountHash}, never a client-supplied one.
	 *
	 * @return whether a device was actually withdrawn -- false for an unknown id, one already revoked, or
	 *     one belonging to a different account.
	 */
	public boolean revokeDevice(long accountHash, String deviceId) {
		if (deviceId == null || deviceId.isBlank()) {
			return false;
		}
		Optional<Credential> found = store.findByTokenHash(deviceId);
		if (found.isEmpty() || !found.get().active() || found.get().accountHash() != accountHash) {
			return false;
		}
		store.revoke(deviceId);
		log.info("Revoked device for account {}", accountHash);
		return true;
	}

	/**
	 * Rename one of the caller's own devices. Same ownership check and the same reason for it as
	 * {@link #revokeDevice} -- {@code deviceId} is a digest, not a secret, so nothing stops a session from
	 * naming another account's device id here without this check.
	 *
	 * @return whether a device was actually renamed -- false for an unknown id, one already revoked, or one
	 *     belonging to a different account.
	 */
	public boolean renameDevice(long accountHash, String deviceId, String label) {
		if (deviceId == null || deviceId.isBlank()) {
			return false;
		}
		Optional<Credential> found = store.findByTokenHash(deviceId);
		if (found.isEmpty() || !found.get().active() || found.get().accountHash() != accountHash) {
			return false;
		}
		store.updateLabel(deviceId, sanitizeLabel(label));
		return true;
	}

	/** Withdraw every machine for an account, for "none of these are mine". */
	public int revokeAll(long accountHash) {
		int revoked = store.revokeAllForAccount(accountHash);
		log.info("Revoked all {} credentials for account {}", revoked, accountHash);
		return revoked;
	}

	/**
	 * Whether the account already has a live credential.
	 */
	public boolean hasActiveCredential(long accountHash) {
		return store.countActiveByAccountHash(accountHash) > 0;
	}

	/**
	 * Generate a fresh coupling code for an account that already has a credential.
	 *
	 * <p>The code is sent to the connected client for that account. The challenger must present
	 * the same code to enrol. Only one pending coupling per account at a time.
	 *
	 * @param accountHash the account to couple for
	 * @param challengerSessionId the session id of the new client waiting to enrol
	 * @return the code to display to the connected client, or empty if one is already pending
	 */
	public Optional<String> generateCouplingCode(long accountHash, String challengerSessionId) {
		String code = String.format("%06d", random.nextInt(1_000_000));
		if (!couplings.putIfAbsent(accountHash, code, challengerSessionId)) {
			// Another challenger is already waiting. Refusing rather than replacing keeps the code on the
			// user's other screen matching the one being asked for.
			return Optional.empty();
		}
		log.info("Generated coupling code for account {}", accountHash);
		return Optional.of(code);
	}

	/**
	 * Drop a pending coupling, for when it cannot be completed -- no machine was online to show the code, so
	 * leaving it pending would block the next attempt for the whole of its lifetime.
	 */
	public void cancelCoupling(long accountHash) {
		couplings.remove(accountHash);
	}

	/**
	 * Whether {@code sessionId} is the connection a still-live coupling code was issued to.
	 *
	 * <p>Exists because {@link #generateCouplingCode} refuses when one is already pending, and the caller
	 * cannot otherwise tell that apart from "coupling is not available". For the session that asked, a
	 * pending code is the opposite of unavailable -- it is on the other screen right now, waiting to be
	 * typed -- so answering "no route" there would talk a user out of the one that was already open.
	 */
	public boolean hasPendingCouplingFor(long accountHash, String sessionId) {
		return couplings.get(accountHash)
			.filter(pending -> pending.challengerSessionId().equals(sessionId))
			.isPresent();
	}

	/**
	 * Validate a coupling code and enrol the challenger if it matches.
	 *
	 * @param accountHash the account the code is for
	 * @param code the code the challenger presents
	 * @param challengerSessionId the session id of the challenger
	 * @param clientIp the challenger's IP
	 * @param label the challenger's device label, as in {@link #enrol}
	 * @return issued credential if valid, empty if code is wrong, expired, or not pending
	 */
	public Optional<Issued> validateCouplingCode(long accountHash, String code, String challengerSessionId,
		String clientIp, String label) {
		Optional<CouplingCodeStore.Pending> found = couplings.get(accountHash);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		CouplingCodeStore.Pending pending = found.get();
		// Compared without short-circuiting on length so a wrong code costs the same time as a right one.
		// Six digits is a small space, and a timing signal on the first differing digit would shrink it to
		// sixty guesses.
		if (!MessageDigest.isEqual(pending.code().getBytes(StandardCharsets.UTF_8),
			code == null ? new byte[0] : code.getBytes(StandardCharsets.UTF_8))) {
			// Six digits is a small enough space to walk if nothing is counting, and nothing was. Burning
			// the pending code rather than the connection is what makes the limit stick: another attempt
			// needs another code, which only appears on the owner's screen.
			if (couplings.recordFailure(accountHash) >= CouplingCodeStore.MAX_ATTEMPTS) {
				log.warn("Dropping the pending coupling for account {} after {} wrong codes",
					accountHash, CouplingCodeStore.MAX_ATTEMPTS);
				couplings.remove(accountHash);
			}
			return Optional.empty();
		}
		// The code was issued to one waiting connection. Anyone else who saw it -- over a shoulder, on a
		// stream -- cannot spend it from their own.
		if (!pending.challengerSessionId().equals(challengerSessionId)) {
			return Optional.empty();
		}
		couplings.remove(accountHash);
		log.info("Coupling code validated for account {}", accountHash);
		// Adds a machine. The one that displayed the code keeps working: the point of coupling is to let
		// somebody prove they hold the account's existing machine, not to make them give it up. An account
		// ends up with the set of machines its owner has actually stood in front of.
		return enrolCoupled(accountHash, clientIp, label);
	}

	// --- Recovery ---

	/**
	 * Issue a fresh set of one-time codes for an account, replacing any unused ones it still has.
	 *
	 * <p>The plaintext exists here and in the one response that carries it. There is no way to see them
	 * again: a "show me my codes" endpoint would be a way for anyone who ever got a session on the account
	 * to walk off with permanent access, which is the opposite of what these are for. Losing the sheet means
	 * generating another one from a device that is still signed in.
	 *
	 * <p>Callers must already have established that the session owns {@code accountHash} -- this method is
	 * handing out ten standing credentials and has no way to check that itself.
	 */
	public List<String> issueRecoveryCodes(long accountHash) {
		if (accountHash == 0 || accountHash == -1) {
			return List.of();
		}
		List<String> codes = mintRecoveryCodes(accountHash);
		log.info("Issued {} recovery codes for account {}", codes.size(), accountHash);
		return codes;
	}

	/** How many of an account's codes are still unspent, for "3 of 10 left" in the device list. */
	public int recoveryCodesRemaining(long accountHash) {
		if (accountHash == 0 || accountHash == -1) {
			return 0;
		}
		return recovery.countUnused(accountHash);
	}

	/**
	 * Spend a recovery code and enrol the machine that presented it.
	 *
	 * <p>This is the path that exists for the case coupling cannot serve: the other machine is not merely
	 * offline, it is gone. Nothing about it needs a second device, which is exactly why the code has to be
	 * long enough that holding it is real evidence.
	 *
	 * <p>The account is checked against the code's own row rather than trusted from the frame. A code
	 * belongs to one account, so a caller naming a different one is either confused or trying a code it
	 * found somewhere; either way it enrols nothing.
	 *
	 * @return the credential issued, or empty when the code is unknown, already spent, or not this
	 *     account's.
	 */
	public Optional<Issued> redeemRecoveryCode(long accountHash, String code, String clientIp, String label) {
		if (accountHash == 0 || accountHash == -1 || code == null) {
			return Optional.empty();
		}
		String normalized = normalizeRecoveryCode(code);
		if (normalized.length() != RECOVERY_CODE_LENGTH) {
			return Optional.empty();
		}
		String codeHash = hash(normalized);
		Optional<AccountRecoveryRepository.RecoveryCode> found = recovery.findByCodeHash(codeHash);
		if (found.isEmpty() || found.get().accountHash() != accountHash) {
			return Optional.empty();
		}
		// Spending it is the same operation as checking it is unspent, so two connections racing on one code
		// cannot both enrol. Checking `unused()` first and updating after would let both through.
		if (!recovery.markUsed(codeHash, clientIp)) {
			log.info("Recovery code for account {} was already spent", accountHash);
			return Optional.empty();
		}
		log.info("Recovery code redeemed for account {} ({} left)",
			accountHash, recovery.countUnused(accountHash));
		return enrolCoupled(accountHash, clientIp, label);
	}

	/**
	 * Enrol a machine that has proved itself some way other than by holding a code -- today, by proving it
	 * owns the Discord account this OSRS account is linked to.
	 *
	 * <p>Deliberately thin. Whether the proof was good is the caller's problem, and every caller of this had
	 * better be able to say precisely why it believes what it believes.
	 */
	public Optional<Issued> enrolVerified(long accountHash, String clientIp, String label, String how) {
		if (accountHash == 0 || accountHash == -1) {
			return Optional.empty();
		}
		log.info("Enrolling a device for account {} on proof: {}", accountHash, how);
		return enrolCoupled(accountHash, clientIp, label);
	}

	private List<String> mintRecoveryCodes(long accountHash) {
		List<String> codes = new java.util.ArrayList<>(RECOVERY_CODE_COUNT);
		List<String> hashes = new java.util.ArrayList<>(RECOVERY_CODE_COUNT);
		for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
			String code = mintRecoveryCode();
			codes.add(group(code));
			hashes.add(hash(code));
		}
		recovery.replaceUnused(accountHash, hashes);
		return List.copyOf(codes);
	}

	private String mintRecoveryCode() {
		StringBuilder out = new StringBuilder(RECOVERY_CODE_LENGTH);
		for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
			out.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
		}
		return out.toString();
	}

	/** {@code ABCD-EFGH-...} for display. Only the ungrouped form is ever hashed or compared. */
	private static String group(String code) {
		StringBuilder out = new StringBuilder(code.length() + code.length() / RECOVERY_GROUP);
		for (int i = 0; i < code.length(); i++) {
			if (i > 0 && i % RECOVERY_GROUP == 0) {
				out.append('-');
			}
			out.append(code.charAt(i));
		}
		return out.toString();
	}

	/**
	 * A typed-in code reduced to the form that was hashed: upper case, no separators, and with the four
	 * characters the alphabet leaves out folded onto the ones they get mistaken for.
	 *
	 * <p>Someone reading a code off paper or a screenshot will type O for zero and l for one, and being told
	 * "that code is wrong" when it is right except for the shape of a glyph is how a recovery path stops
	 * being one. Nothing is lost by accepting them: the alphabet has no I, L, O or U to be ambiguous with.
	 */
	static String normalizeRecoveryCode(String code) {
		StringBuilder out = new StringBuilder(RECOVERY_CODE_LENGTH);
		for (char c : code.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
			char folded = switch (c) {
				case 'O' -> '0';
				case 'I', 'L' -> '1';
				case 'U' -> 'V';
				default -> c;
			};
			if (RECOVERY_ALPHABET.indexOf(folded) >= 0) {
				out.append(folded);
			}
		}
		return out.toString();
	}

	/**
	 * Enrol a machine that has just proved itself by coupling.
	 *
	 * <p>Separate from {@link #enrol} because that one refuses an account which already has a credential --
	 * which is what sends a second machine here in the first place.
	 */
	private Optional<Issued> enrolCoupled(long accountHash, String clientIp, String label) {
		int existing = store.countActiveByAccountHash(accountHash);
		if (existing >= MAX_DEVICES) {
			log.warn("Refusing coupled enrolment for account {}: already has {} devices", accountHash, existing);
			return Optional.empty();
		}
		String token = mintToken();
		store.insert(hash(token), accountHash, sanitizeLabel(label));
		store.logEnrolment(accountHash, clientIp, false);
		log.info("Coupled a device for account {} (device {} of this account)", accountHash, existing + 1);
		// No codes: the account already has a set, and quietly replacing it here would invalidate the sheet
		// the user is holding at the exact moment they proved they still need it.
		return Optional.of(new Issued(token, accountHash, false, List.of()));
	}

	/**
	 * A client-reported device label, made safe to store: trimmed, capped to the column's width, and folded
	 * to null when blank so the UI's "no label" fallback (the enrolment date) kicks in instead of showing an
	 * empty string.
	 */
	private static String sanitizeLabel(String label) {
		if (label == null) {
			return null;
		}
		String trimmed = label.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
	}

	private String mintToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * What is stored in place of the token.
	 *
	 * <p>A plain digest rather than a password hash on purpose: this is a 256-bit value we generated from
	 * {@link SecureRandom}, not something a person chose, so there is no dictionary to slow down and the
	 * work factor would buy nothing but latency on a lookup that happens per handshake.
	 */
	private static String hash(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}

package net.osparty.api.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.osparty.api.repository.AccountCredentialRepository;
import net.osparty.api.repository.AccountCredentialRepository.Credential;
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
	private static final int MAX_DEVICES = 1;

	/** Six digits, numeric, displayed as three groups of two. */
	private static final int COUPLING_CODE_LENGTH = 6;

	/** Seconds a coupling code remains valid. Long enough to type; short enough that a stale code is not an invite. */
	private static final int COUPLING_CODE_TTL = 300;

	private final AccountCredentialRepository store;
	private final SecureRandom random = new SecureRandom();
	private final boolean enrolmentEnabled;
	private final Map<Long, PendingCoupling> pendingCouplings = new ConcurrentHashMap<>();

	/** A pending coupling request: the code, when it expires, and the session id of the challenger. */
	record PendingCoupling(String code, Instant expiresAt, String challengerSessionId) {
		public boolean expired() {
			return Instant.now().isAfter(expiresAt);
		}
	}

	public AccountAuthService(AccountCredentialRepository store,
		// Enrolment stays off until raw account hashes have stopped leaving the server (research doc
		// §10.1). Verification of already-issued credentials is always on: it can only ever refuse.
		@Value("${app.auth.enrolment-enabled:true}") boolean enrolmentEnabled) {
		this.store = store;
		this.enrolmentEnabled = enrolmentEnabled;
	}

	/** A freshly issued credential. The plaintext exists here and in the response, then never again. */
	public record Issued(String token, long accountHash, boolean firstDevice) {
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
	 */
	public Optional<Issued> enrol(long accountHash, String clientIp) {
		if (!enrolmentEnabled) {
			return Optional.empty();
		}
		if (accountHash == 0 || accountHash == -1) {
			// No account to bind to. Both spellings of "logged out" are folded at decode, but a caller
			// reaching here with one has skipped that and must not enrol every logged-out client as one
			// shared identity.
			return Optional.empty();
		}
		int existing = store.countActiveByAccountHash(accountHash);
		if (existing >= MAX_DEVICES) {
			log.warn("Refusing enrolment for account {}: already has {} devices", accountHash, existing);
			return Optional.empty();
		}
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		store.insert(hash(token), accountHash, null);
		store.logEnrolment(accountHash, clientIp, existing == 0);
		log.info("Enrolled a device for account {} (device {} of this account)", accountHash, existing + 1);
		return Optional.of(new Issued(token, accountHash, existing == 0));
	}

	/** The machines currently entitled to speak for an account. */
	public List<Credential> devices(long accountHash) {
		return store.findActiveByAccountHash(accountHash);
	}

	/** Withdraw one machine's credential; the account keeps its others. */
	public void revoke(String token) {
		if (token != null && !token.isBlank()) {
			store.revoke(hash(token));
		}
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
		if (pendingCouplings.containsKey(accountHash)) {
			return Optional.empty();
		}
		String code = String.format("%06d", random.nextInt(1_000_000));
		PendingCoupling pending = new PendingCoupling(code, Instant.now().plusSeconds(COUPLING_CODE_TTL), challengerSessionId);
		pendingCouplings.put(accountHash, pending);
		log.info("Generated coupling code for account {}", accountHash);
		return Optional.of(code);
	}

	/**
	 * Validate a coupling code and enrol the challenger if it matches.
	 *
	 * @param accountHash the account the code is for
	 * @param code the code the challenger presents
	 * @param challengerSessionId the session id of the challenger
	 * @param clientIp the challenger's IP
	 * @return issued credential if valid, empty if code is wrong, expired, or not pending
	 */
	public Optional<Issued> validateCouplingCode(long accountHash, String code, String challengerSessionId, String clientIp) {
		PendingCoupling pending = pendingCouplings.get(accountHash);
		if (pending == null) {
			return Optional.empty();
		}
		if (pending.expired()) {
			pendingCouplings.remove(accountHash, pending);
			return Optional.empty();
		}
		if (!pending.code().equals(code)) {
			return Optional.empty();
		}
		if (!pending.challengerSessionId().equals(challengerSessionId)) {
			return Optional.empty();
		}
		pendingCouplings.remove(accountHash, pending);
		log.info("Coupling code validated for account {}", accountHash);
		store.revokeAllForAccount(accountHash);
		return enrol(accountHash, clientIp);
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

package net.osparty.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.osparty.api.repository.InMemoryAccountCredentialRepository;
import net.osparty.api.repository.InMemoryAccountRecoveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Recovery codes: the way back onto an account when the machine that holds it is not offline but gone.
 *
 * <p>Coupling covers "both computers are in front of me". Everything here covers the case it cannot -- a
 * dead drive, a reinstall, a machine sold -- which is the case that actually costs someone their account,
 * because enrolment is first-come-first-served and the first came from somewhere that no longer exists.
 *
 * <p>These codes are standing credentials with no expiry and nothing else standing behind them, so the
 * properties worth pinning are the ones that stop one being spent twice, spent on the wrong account, or
 * quietly replaced at the moment somebody needs it.
 */
class AccountRecoveryTest {
	private static final long ACCOUNT = 4242L;
	private static final long OTHER_ACCOUNT = 9999L;

	private InMemoryAccountCredentialRepository credentials;
	private InMemoryAccountRecoveryRepository recovery;
	private AccountAuthService auth;

	@BeforeEach
	void setUp() {
		credentials = new InMemoryAccountCredentialRepository();
		recovery = new InMemoryAccountRecoveryRepository();
		auth = new AccountAuthService(credentials, recovery, new LocalCouplingCodeStore(), true);
	}

	/**
	 * The one moment an account is certain to have both a user's attention and exactly one way back in.
	 * Asking later means asking someone who has not yet had the problem.
	 */
	@Test
	void anAccountsFirstDeviceComesWithRecoveryCodes() {
		AccountAuthService.Issued issued = auth.enrol(ACCOUNT, "1.2.3.4", null).orElseThrow();

		assertThat(issued.recoveryCodes()).isNotEmpty();
		assertThat(auth.recoveryCodesRemaining(ACCOUNT)).isEqualTo(issued.recoveryCodes().size());
	}

	@Test
	void aCodeSignsInAMachineThatCanProveNothingElse() {
		String code = auth.enrol(ACCOUNT, null, null).orElseThrow().recoveryCodes().get(0);

		AccountAuthService.Issued recovered =
			auth.redeemRecoveryCode(ACCOUNT, code, "5.6.7.8", "laptop").orElseThrow();

		assertThat(auth.accountFor(recovered.token())).contains(ACCOUNT);
	}

	/** Recovery adds a machine. The one that was already there keeps working. */
	@Test
	void theExistingDeviceSurvivesARecovery() {
		AccountAuthService.Issued first = auth.enrol(ACCOUNT, null, null).orElseThrow();

		auth.redeemRecoveryCode(ACCOUNT, first.recoveryCodes().get(0), null, null).orElseThrow();

		assertThat(auth.accountFor(first.token())).contains(ACCOUNT);
	}

	@Test
	void aCodeCannotBeSpentTwice() {
		String code = auth.enrol(ACCOUNT, null, null).orElseThrow().recoveryCodes().get(0);
		auth.redeemRecoveryCode(ACCOUNT, code, null, null).orElseThrow();

		assertThat(auth.redeemRecoveryCode(ACCOUNT, code, null, null)).isEmpty();
	}

	/**
	 * A code belongs to one account. Naming a different one is either a mistake or somebody trying a code
	 * they found, and either way it enrols nothing.
	 */
	@Test
	void aCodeDoesNotWorkOnAnotherAccount() {
		String code = auth.enrol(ACCOUNT, null, null).orElseThrow().recoveryCodes().get(0);
		auth.enrol(OTHER_ACCOUNT, null, null);

		assertThat(auth.redeemRecoveryCode(OTHER_ACCOUNT, code, null, null)).isEmpty();
		assertThat(auth.recoveryCodesRemaining(ACCOUNT))
			.as("a code offered against the wrong account must not be consumed")
			.isEqualTo(10);
	}

	@Test
	void nonsenseIsRefusedWithoutSpendingAnything() {
		auth.enrol(ACCOUNT, null, null);

		assertThat(auth.redeemRecoveryCode(ACCOUNT, "not-a-code", null, null)).isEmpty();
		assertThat(auth.redeemRecoveryCode(ACCOUNT, "", null, null)).isEmpty();
		assertThat(auth.redeemRecoveryCode(ACCOUNT, null, null, null)).isEmpty();
		assertThat(auth.recoveryCodesRemaining(ACCOUNT)).isEqualTo(10);
	}

	/** The plaintext is not kept, so this table is no more a set of working codes than the credential one. */
	@Test
	void thePlaintextCodeIsNotStored() {
		String code = auth.enrol(ACCOUNT, null, null).orElseThrow().recoveryCodes().get(0);

		assertThat(recovery.findByCodeHash(code)).isEmpty();
		assertThat(recovery.findByCodeHash(code.replace("-", ""))).isEmpty();
	}

	/**
	 * Someone reading a code off paper types O for zero and l for one. Refusing a code that is right except
	 * for the shape of a glyph is how a recovery path stops being one -- and nothing is lost by accepting
	 * them, because the alphabet deliberately contains no I, L, O or U to be confused with.
	 */
	@Test
	void aCodeIsAcceptedHoweverItWasTypedOut() {
		String code = auth.enrol(ACCOUNT, null, null).orElseThrow().recoveryCodes().get(0);
		String mangled = code.toLowerCase(java.util.Locale.ROOT)
			.replace('0', 'O')
			.replace('1', 'l')
			.replace("-", " ");

		assertThat(auth.redeemRecoveryCode(ACCOUNT, mangled, null, null)).isPresent();
	}

	/**
	 * Regenerating means the old sheet stops working, which is what someone who has just decided their old
	 * codes are compromised expects it to mean.
	 */
	@Test
	void issuingAFreshSetRetiresTheOldOne() {
		String old = auth.enrol(ACCOUNT, null, null).orElseThrow().recoveryCodes().get(0);

		List<String> replacements = auth.issueRecoveryCodes(ACCOUNT);

		assertThat(auth.redeemRecoveryCode(ACCOUNT, old, null, null)).isEmpty();
		assertThat(auth.redeemRecoveryCode(ACCOUNT, replacements.get(0), null, null)).isPresent();
	}

	/**
	 * Coupling would otherwise quietly invalidate the sheet the user is holding at the exact moment they
	 * proved they still need it.
	 */
	@Test
	void couplingASecondDeviceLeavesTheCodesAlone() {
		AccountAuthService.Issued first = auth.enrol(ACCOUNT, null, null).orElseThrow();
		String code = auth.generateCouplingCode(ACCOUNT, "challenger").orElseThrow();

		AccountAuthService.Issued second =
			auth.validateCouplingCode(ACCOUNT, code, "challenger", null, null).orElseThrow();

		assertThat(second.recoveryCodes()).isEmpty();
		assertThat(auth.redeemRecoveryCode(ACCOUNT, first.recoveryCodes().get(0), null, null)).isPresent();
	}

	/** Codes are per account, not per device: an account with three machines still has one sheet. */
	@Test
	void everyDeviceOnAnAccountSharesTheOneSetOfCodes() {
		auth.enrol(ACCOUNT, null, null);
		String code = auth.generateCouplingCode(ACCOUNT, "challenger").orElseThrow();
		auth.validateCouplingCode(ACCOUNT, code, "challenger", null, null);

		assertThat(auth.recoveryCodesRemaining(ACCOUNT)).isEqualTo(10);
	}

	/**
	 * Six digits is a million, and the code lives five minutes -- which is only out of reach if something is
	 * counting. Nothing was. Burning the pending code rather than the connection is what makes the limit
	 * stick, because the next attempt then needs a code that only appears on the owner's screen.
	 */
	@Test
	void aRunOfWrongCouplingCodesBurnsThePendingOne() {
		auth.enrol(ACCOUNT, null, null);
		String real = auth.generateCouplingCode(ACCOUNT, "challenger").orElseThrow();

		for (int attempt = 0; attempt < CouplingCodeStore.MAX_ATTEMPTS; attempt++) {
			assertThat(auth.validateCouplingCode(ACCOUNT, "000000", "challenger", null, null)).isEmpty();
		}

		assertThat(auth.validateCouplingCode(ACCOUNT, real, "challenger", null, null))
			.as("the real code must stop working once its attempts are spent")
			.isEmpty();
	}

	/** A mistyped digit or two must not cost the user the code that is on their other screen. */
	@Test
	void aCoupleOfWrongCodesDoesNotBurnThePendingOne() {
		auth.enrol(ACCOUNT, null, null);
		String real = auth.generateCouplingCode(ACCOUNT, "challenger").orElseThrow();

		auth.validateCouplingCode(ACCOUNT, "000000", "challenger", null, null);
		auth.validateCouplingCode(ACCOUNT, "111111", "challenger", null, null);

		assertThat(auth.validateCouplingCode(ACCOUNT, real, "challenger", null, null)).isPresent();
	}

	/**
	 * {@code enrolVerified} is the seam Discord recovery enrols through. It is deliberately thin -- whether
	 * the proof was good is settled before it is called -- so what matters here is that it behaves like
	 * coupling rather than like a first enrolment.
	 */
	@Test
	void enrolVerifiedAddsADeviceWithoutReplacingTheCodes() {
		AccountAuthService.Issued first = auth.enrol(ACCOUNT, null, null).orElseThrow();

		AccountAuthService.Issued added =
			auth.enrolVerified(ACCOUNT, "5.6.7.8", "laptop", "Discord").orElseThrow();

		assertThat(auth.accountFor(added.token())).contains(ACCOUNT);
		assertThat(added.firstDevice()).isFalse();
		assertThat(added.recoveryCodes()).isEmpty();
		assertThat(auth.accountFor(first.token())).contains(ACCOUNT);
	}

	/** A logged-out client reports a sentinel rather than an account, and must not enrol as one. */
	@Test
	void thereIsNothingToRecoverForALoggedOutClient() {
		assertThat(auth.redeemRecoveryCode(0L, "whatever", null, null)).isEmpty();
		assertThat(auth.redeemRecoveryCode(-1L, "whatever", null, null)).isEmpty();
		assertThat(auth.enrolVerified(-1L, null, null, "Discord")).isEmpty();
		assertThat(auth.issueRecoveryCodes(-1L)).isEmpty();
	}

	/** Revoking a device is not the same as revoking the account's way back in. */
	@Test
	void revokingEveryDeviceLeavesTheCodesUsable() {
		String code = auth.enrol(ACCOUNT, null, null).orElseThrow().recoveryCodes().get(0);
		auth.revokeAll(ACCOUNT);

		assertThat(auth.redeemRecoveryCode(ACCOUNT, code, null, null)).isPresent();
	}
}

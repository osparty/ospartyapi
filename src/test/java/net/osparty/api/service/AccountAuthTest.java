package net.osparty.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.osparty.api.repository.InMemoryAccountCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The credential is the identity: once issued, it decides which account a connection speaks for, and the
 * account hash in a frame stops mattering. These cover the parts that make that safe -- that a token resolves
 * only to the account it was issued for, that withdrawing one works, and that the public id shown to other
 * players gives nothing away about the hash behind it.
 */
class AccountAuthTest {
	private InMemoryAccountCredentialRepository store;
	private AccountAuthService auth;

	@BeforeEach
	void setUp() {
		store = new InMemoryAccountCredentialRepository();
		auth = new AccountAuthService(store, true);
	}

	@Test
	void anIssuedTokenResolvesToTheAccountItWasIssuedFor() {
		AccountAuthService.Issued issued = auth.enrol(4242L, "1.2.3.4").orElseThrow();

		assertThat(auth.accountFor(issued.token())).contains(4242L);
		assertThat(issued.firstDevice()).isTrue();
	}

	@Test
	void anythingWeDidNotIssueResolvesToNobody() {
		auth.enrol(4242L, null);

		assertThat(auth.accountFor("not-a-token-we-minted")).isEmpty();
		assertThat(auth.accountFor("")).isEmpty();
		assertThat(auth.accountFor(null)).isEmpty();
	}

	/** The token is never stored, so a dump of the table is not a set of working credentials. */
	@Test
	void thePlaintextTokenIsNotKept() {
		AccountAuthService.Issued issued = auth.enrol(4242L, null).orElseThrow();

		assertThat(store.findActiveByAccountHash(4242L))
			.singleElement()
			.satisfies(credential -> assertThat(credential.tokenHash()).isNotEqualTo(issued.token()));
	}

	/** One person, two machines. The account is the identity; credentials are what may speak for it. */
	@Test
	void anAccountCanHoldSeveralDevices() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null).orElseThrow();
		AccountAuthService.Issued laptop = auth.enrol(4242L, null).orElseThrow();

		assertThat(laptop.firstDevice()).isFalse();
		assertThat(auth.accountFor(desktop.token())).contains(4242L);
		assertThat(auth.accountFor(laptop.token())).contains(4242L);
		assertThat(auth.devices(4242L)).hasSize(2);
	}

	@Test
	void revokingOneDeviceLeavesTheOthers() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null).orElseThrow();
		AccountAuthService.Issued laptop = auth.enrol(4242L, null).orElseThrow();

		auth.revoke(desktop.token());

		assertThat(auth.accountFor(desktop.token())).isEmpty();
		assertThat(auth.accountFor(laptop.token())).contains(4242L);
	}

	@Test
	void revokingEverythingSignsTheAccountOutOfEveryMachine() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null).orElseThrow();
		AccountAuthService.Issued laptop = auth.enrol(4242L, null).orElseThrow();

		assertThat(auth.revokeAll(4242L)).isEqualTo(2);

		assertThat(auth.accountFor(desktop.token())).isEmpty();
		assertThat(auth.accountFor(laptop.token())).isEmpty();
	}

	/**
	 * Enrolment is the one moment this scheme takes an account hash on faith, so it is never silent. The
	 * record has to outlive the credential -- a hijack is discovered after the fact or not at all.
	 */
	@Test
	void everyEnrolmentIsRecorded() {
		auth.enrol(4242L, "1.2.3.4");
		auth.enrol(4242L, "5.6.7.8");

		assertThat(store.enrolments()).hasSize(2);
		assertThat(store.enrolments().get(0).firstDevice()).isTrue();
		assertThat(store.enrolments().get(1).firstDevice()).isFalse();
		assertThat(store.enrolments().get(1).clientIp()).isEqualTo("5.6.7.8");
	}

	/** Ships off. A client that gets no credential carries on unauthenticated, as every old client does. */
	@Test
	void enrolmentIssuesNothingWhileItIsSwitchedOff() {
		AccountAuthService disabled = new AccountAuthService(store, false);

		assertThat(disabled.enrol(4242L, null)).isEmpty();
		assertThat(store.findActiveByAccountHash(4242L)).isEmpty();
	}

	/** Both spellings of "logged out". Enrolling one would bind every logged-out client to one identity. */
	@Test
	void thereIsNoAccountToEnrolWhenNobodyIsLoggedIn() {
		assertThat(auth.enrol(0L, null)).isEmpty();
		assertThat(auth.enrol(-1L, null)).isEmpty();
	}

	// ---- public player id ---------------------------------------------------

	@Test
	void aPlayerIdIsStableAndDistinct() {
		PlayerIdService ids = new PlayerIdService("a-test-salt");

		assertThat(ids.of(4242L)).isEqualTo(ids.of(4242L));
		assertThat(ids.of(4242L)).isNotEqualTo(ids.of(4243L));
	}

	/** Shown beside a name so a rename reads as a rename. Grouped because people read these aloud. */
	@Test
	void aPlayerIdIsReadableAndCarriesSixtyBits() {
		PlayerIdService ids = new PlayerIdService("a-test-salt");

		String id = ids.of(4242L);

		assertThat(id).matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}");
	}

	/** The salt is what stops the id being reversible; two deployments must not agree on the mapping. */
	@Test
	void adifferentSaltGivesADifferentId() {
		assertThat(new PlayerIdService("one").of(4242L))
			.isNotEqualTo(new PlayerIdService("two").of(4242L));
	}

	/** No account, no id. A placeholder several people shared would defeat what the id is for. */
	@Test
	void thereIsNoPlayerIdWithoutAnAccount() {
		PlayerIdService ids = new PlayerIdService("a-test-salt");

		assertThat(ids.of(0L)).isNull();
		assertThat(ids.of(-1L)).isNull();
	}
}

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
		auth = new AccountAuthService(store, new LocalCouplingCodeStore(), true);
	}

	@Test
	void anIssuedTokenResolvesToTheAccountItWasIssuedFor() {
		AccountAuthService.Issued issued = auth.enrol(4242L, "1.2.3.4", null).orElseThrow();

		assertThat(auth.accountFor(issued.token())).contains(4242L);
		assertThat(issued.firstDevice()).isTrue();
	}

	@Test
	void anythingWeDidNotIssueResolvesToNobody() {
		auth.enrol(4242L, null, null);

		assertThat(auth.accountFor("not-a-token-we-minted")).isEmpty();
		assertThat(auth.accountFor("")).isEmpty();
		assertThat(auth.accountFor(null)).isEmpty();
	}

	/** The token is never stored, so a dump of the table is not a set of working credentials. */
	@Test
	void thePlaintextTokenIsNotKept() {
		AccountAuthService.Issued issued = auth.enrol(4242L, null, null).orElseThrow();

		assertThat(store.findActiveByAccountHash(4242L))
			.singleElement()
			.satisfies(credential -> assertThat(credential.tokenHash()).isNotEqualTo(issued.token()));
	}

	/**
	 * One machine at a time. A second one does not quietly enrol alongside the first -- it has to go through
	 * coupling, which is what makes claiming somebody else's account hash worth nothing on its own.
	 */
	@Test
	void aSecondMachineDoesNotEnrolOnItsOwn() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null, null).orElseThrow();

		assertThat(auth.enrol(4242L, null, null)).isEmpty();
		assertThat(auth.accountFor(desktop.token())).contains(4242L);
		assertThat(auth.devices(4242L)).hasSize(1);
	}

	@Test
	void revokingSignsTheMachineOut() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null, null).orElseThrow();

		auth.revoke(desktop.token());

		assertThat(auth.accountFor(desktop.token())).isEmpty();
		assertThat(auth.hasActiveCredential(4242L)).isFalse();
	}

	/** With nothing left holding the account, the next machine is a first machine again. */
	@Test
	void anAccountWithNoMachinesEnrolsPlainlyAgain() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null, null).orElseThrow();
		auth.revoke(desktop.token());

		assertThat(auth.enrol(4242L, null, null)).isPresent();
	}

	/**
	 * Enrolment is the one moment this scheme takes an account hash on faith, so it is never silent. The
	 * record has to outlive the credential -- a hijack is discovered after the fact or not at all.
	 */
	@Test
	void everyEnrolmentIsRecorded() {
		auth.enrol(4242L, "1.2.3.4", null);

		assertThat(store.enrolments()).hasSize(1);
		assertThat(store.enrolments().get(0).firstDevice()).isTrue();
		assertThat(store.enrolments().get(0).clientIp()).isEqualTo("1.2.3.4");
	}

	// ---- revoking a device you cannot reach ---------------------------------

	/**
	 * The whole point of {@link AccountAuthService#revokeDevice}: it works from a machine that never held
	 * the lost one's token, which {@link AccountAuthService#revoke} cannot do.
	 */
	@Test
	void revokeDeviceWithdrawsAMachineByIdFromAnotherMachine() {
		AccountAuthService.Issued lost = auth.enrol(4242L, null, null).orElseThrow();
		AccountAuthService.Issued current = auth.validateCouplingCode(4242L,
			auth.generateCouplingCode(4242L, "current").orElseThrow(), "current", null, null).orElseThrow();
		String lostDeviceId = auth.devices(4242L).stream()
			.filter(d -> !d.tokenHash().equals(digestOf(current.token())))
			.findFirst().orElseThrow().tokenHash();

		assertThat(auth.revokeDevice(4242L, lostDeviceId)).isTrue();

		assertThat(auth.accountFor(lost.token())).isEmpty();
		assertThat(auth.accountFor(current.token())).contains(4242L);
	}

	/** A device id only means something on the account it belongs to. */
	@Test
	void revokeDeviceRefusesADeviceBelongingToAnotherAccount() {
		AccountAuthService.Issued other = auth.enrol(9999L, null, null).orElseThrow();
		String otherDeviceId = auth.devices(9999L).get(0).tokenHash();

		assertThat(auth.revokeDevice(4242L, otherDeviceId)).isFalse();
		assertThat(auth.accountFor(other.token())).contains(9999L);
	}

	@Test
	void revokeDeviceIsFalseForAnUnknownOrAlreadyRevokedId() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null, null).orElseThrow();
		String deviceId = auth.devices(4242L).get(0).tokenHash();
		auth.revoke(desktop.token());

		assertThat(auth.revokeDevice(4242L, deviceId)).isFalse();
		assertThat(auth.revokeDevice(4242L, "not-a-real-id")).isFalse();
	}

	// ---- device labels -------------------------------------------------------

	@Test
	void enrolCapturesTheReportedDeviceLabel() {
		auth.enrol(4242L, null, "DESKTOP-ABC123");

		assertThat(auth.devices(4242L)).singleElement()
			.satisfies(d -> assertThat(d.label()).isEqualTo("DESKTOP-ABC123"));
	}

	/** A blank label reads as "none reported", not as an empty string sitting in the device list. */
	@Test
	void enrolFoldsABlankLabelToNull() {
		auth.enrol(4242L, null, "   ");

		assertThat(auth.devices(4242L)).singleElement().satisfies(d -> assertThat(d.label()).isNull());
	}

	/** The column is 64 chars wide; a client that reports more must not fail the enrolment over it. */
	@Test
	void enrolTruncatesAnOverlongLabel() {
		auth.enrol(4242L, null, "x".repeat(100));

		assertThat(auth.devices(4242L)).singleElement()
			.satisfies(d -> assertThat(d.label()).hasSize(64));
	}

	@Test
	void renameDeviceChangesTheLabelFromAnotherMachine() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null, "Desktop").orElseThrow();
		String deviceId = auth.devices(4242L).get(0).tokenHash();

		assertThat(auth.renameDevice(4242L, deviceId, "Home PC")).isTrue();

		assertThat(auth.devices(4242L)).singleElement()
			.satisfies(d -> assertThat(d.label()).isEqualTo("Home PC"));
		assertThat(auth.accountFor(desktop.token())).contains(4242L);
	}

	/** Same ownership boundary as revokeDevice, and for the same reason: a digest is not a secret. */
	@Test
	void renameDeviceRefusesADeviceBelongingToAnotherAccount() {
		auth.enrol(9999L, null, "Other's PC");
		String otherDeviceId = auth.devices(9999L).get(0).tokenHash();

		assertThat(auth.renameDevice(4242L, otherDeviceId, "Mine now")).isFalse();
		assertThat(auth.devices(9999L)).singleElement()
			.satisfies(d -> assertThat(d.label()).isEqualTo("Other's PC"));
	}

	@Test
	void renameDeviceIsFalseForAnUnknownOrAlreadyRevokedId() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null, null).orElseThrow();
		String deviceId = auth.devices(4242L).get(0).tokenHash();
		auth.revoke(desktop.token());

		assertThat(auth.renameDevice(4242L, deviceId, "New name")).isFalse();
		assertThat(auth.renameDevice(4242L, "not-a-real-id", "New name")).isFalse();
	}

	/** Only ever exposed as a digest that authenticates nothing on its own; a test SHA-256 recreation. */
	private static String digestOf(String token) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			return java.util.HexFormat.of().formatHex(
				digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---- coupling a second machine ------------------------------------------

	/**
	 * Coupling adds a machine. The one that displayed the code keeps working -- the point is to let someone
	 * prove they hold the account's existing machine, not to make them give it up.
	 */
	@Test
	void aValidCodeAddsAMachineWithoutTakingTheFirst() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null, null).orElseThrow();
		String code = auth.generateCouplingCode(4242L, "challenger").orElseThrow();

		AccountAuthService.Issued laptop =
			auth.validateCouplingCode(4242L, code, "challenger", "1.2.3.4", null).orElseThrow();

		assertThat(auth.accountFor(laptop.token())).contains(4242L);
		assertThat(auth.accountFor(desktop.token())).contains(4242L);
		assertThat(auth.devices(4242L)).hasSize(2);
	}

	/** And again, so an account accumulates the machines its owner has actually stood in front of. */
	@Test
	void aThirdMachineCouplesTheSameWay() {
		AccountAuthService.Issued first = auth.enrol(4242L, null, null).orElseThrow();
		AccountAuthService.Issued second = auth.validateCouplingCode(4242L,
			auth.generateCouplingCode(4242L, "second").orElseThrow(), "second", null, null).orElseThrow();
		AccountAuthService.Issued third = auth.validateCouplingCode(4242L,
			auth.generateCouplingCode(4242L, "third").orElseThrow(), "third", null, null).orElseThrow();

		assertThat(auth.accountFor(first.token())).contains(4242L);
		assertThat(auth.accountFor(second.token())).contains(4242L);
		assertThat(auth.accountFor(third.token())).contains(4242L);
	}

	/** Signing one machine out leaves the rest alone: they are separate credentials, not one shared seat. */
	@Test
	void revokingOneCoupledMachineLeavesTheOthers() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null, null).orElseThrow();
		AccountAuthService.Issued laptop = auth.validateCouplingCode(4242L,
			auth.generateCouplingCode(4242L, "challenger").orElseThrow(), "challenger", null, null).orElseThrow();

		auth.revoke(laptop.token());

		assertThat(auth.accountFor(laptop.token())).isEmpty();
		assertThat(auth.accountFor(desktop.token())).contains(4242L);
	}

	@Test
	void aWrongCodeAddsNothing() {
		AccountAuthService.Issued desktop = auth.enrol(4242L, null, null).orElseThrow();
		auth.generateCouplingCode(4242L, "challenger");

		assertThat(auth.validateCouplingCode(4242L, "000000", "challenger", null, null)).isEmpty();
		assertThat(auth.accountFor(desktop.token())).contains(4242L);
	}

	/**
	 * The code is issued to one waiting session and is only good there. Otherwise a bystander who saw the
	 * code -- a stream, a shared screen -- could spend it from their own connection.
	 */
	@Test
	void aCodeIsOnlyGoodForTheSessionItWasIssuedTo() {
		auth.enrol(4242L, null, null);
		String code = auth.generateCouplingCode(4242L, "challenger").orElseThrow();

		assertThat(auth.validateCouplingCode(4242L, code, "somebody-else", null, null)).isEmpty();
		// Still spendable by the session it belongs to: a refusal must not consume it.
		assertThat(auth.validateCouplingCode(4242L, code, "challenger", null, null)).isPresent();
	}

	@Test
	void aCodeCannotBeSpentTwice() {
		auth.enrol(4242L, null, null);
		String code = auth.generateCouplingCode(4242L, "challenger").orElseThrow();
		auth.validateCouplingCode(4242L, code, "challenger", null, null);

		assertThat(auth.validateCouplingCode(4242L, code, "challenger", null, null)).isEmpty();
	}

	/** One request at a time, so a second challenger cannot start a race for the same account. */
	@Test
	void onlyOneCouplingIsPendingAtATime() {
		auth.enrol(4242L, null, null);
		auth.generateCouplingCode(4242L, "challenger").orElseThrow();

		assertThat(auth.generateCouplingCode(4242L, "another")).isEmpty();
	}

	/**
	 * A request that was never completed must not become a permanent lockout. Cancelling is what the
	 * "nobody was online to read the code" path does, and the next attempt has to work.
	 */
	@Test
	void anAbandonedCouplingDoesNotBlockTheNextOne() {
		auth.enrol(4242L, null, null);
		auth.generateCouplingCode(4242L, "challenger").orElseThrow();

		auth.cancelCoupling(4242L);

		assertThat(auth.generateCouplingCode(4242L, "another")).isPresent();
	}

	/** Six digits, so it can be read off one screen and typed into another without ambiguity. */
	@Test
	void theCodeIsSixDigits() {
		auth.enrol(4242L, null, null);

		assertThat(auth.generateCouplingCode(4242L, "challenger").orElseThrow()).matches("\\d{6}");
	}

	/** Ships off. A client that gets no credential carries on unauthenticated, as every old client does. */
	@Test
	void enrolmentIssuesNothingWhileItIsSwitchedOff() {
		AccountAuthService disabled = new AccountAuthService(store, new LocalCouplingCodeStore(), false);

		assertThat(disabled.enrol(4242L, null, null)).isEmpty();
		assertThat(store.findActiveByAccountHash(4242L)).isEmpty();
	}

	/** Both spellings of "logged out". Enrolling one would bind every logged-out client to one identity. */
	@Test
	void thereIsNoAccountToEnrolWhenNobodyIsLoggedIn() {
		assertThat(auth.enrol(0L, null, null)).isEmpty();
		assertThat(auth.enrol(-1L, null, null)).isEmpty();
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

	// ---- enriching board ads with playerId ----------------------------------

	/**
	 * The board's ad member list is a second place {@code accountHash} rides to a client, entirely separate
	 * from the live-party roster -- {@code enrichAds} is what lets it carry {@code playerId} instead of
	 * relying on it, the same way {@code DiscordBadgeService.enrichAds} adds badges. See
	 * WEBSOCKET_AUTH_RESEARCH.md §10.3.
	 */
	@Test
	void enrichAdsStampsAPlayerIdOnEachMemberWithAnAccount() {
		PlayerIdService ids = new PlayerIdService("a-test-salt");
		net.osparty.api.model.Advertisement ad = new net.osparty.api.model.Advertisement();
		ad.setMembers(java.util.List.of(
			new net.osparty.api.model.Member("Alice", 4242L),
			new net.osparty.api.model.Member("Bob", 0L))); // no account -- nothing to enrich

		net.osparty.api.model.Advertisement enriched = ids.enrichAds(java.util.List.of(ad)).get(0);

		java.util.List<net.osparty.api.model.Member> members = enriched.getMembers();
		assertThat(members.get(0).getPlayerId()).isEqualTo(ids.of(4242L));
		assertThat(members.get(1).getPlayerId()).isNull();
	}

	/** accountHash keeps riding alongside playerId during the transition -- this only adds a field. */
	@Test
	void enrichAdsDoesNotRemoveAccountHash() {
		PlayerIdService ids = new PlayerIdService("a-test-salt");
		net.osparty.api.model.Advertisement ad = new net.osparty.api.model.Advertisement();
		ad.setMembers(java.util.List.of(new net.osparty.api.model.Member("Alice", 4242L)));

		net.osparty.api.model.Advertisement enriched = ids.enrichAds(java.util.List.of(ad)).get(0);

		assertThat(enriched.getMembers().get(0).getAccountHash()).isEqualTo(4242L);
	}

	/** The stored ad is untouched -- playerId is computed fresh for the wire, never persisted. */
	@Test
	void enrichAdsDoesNotMutateTheOriginal() {
		PlayerIdService ids = new PlayerIdService("a-test-salt");
		net.osparty.api.model.Advertisement ad = new net.osparty.api.model.Advertisement();
		ad.setMembers(java.util.List.of(new net.osparty.api.model.Member("Alice", 4242L)));

		ids.enrichAds(java.util.List.of(ad));

		assertThat(ad.getMembers().get(0).getPlayerId()).isNull();
	}

	@Test
	void enrichAdsLeavesAnAdWithNoMembersAlone() {
		PlayerIdService ids = new PlayerIdService("a-test-salt");
		net.osparty.api.model.Advertisement ad = new net.osparty.api.model.Advertisement();

		assertThat(ids.enrichAds(java.util.List.of(ad)).get(0)).isSameAs(ad);
	}
}

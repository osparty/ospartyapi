package net.osparty.api.party;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.osparty.api.transport.SocketSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Auto-admission comes from a grant this service made, not from the joiner saying it was invited.
 *
 * <p>This is the difference between a party whose room key is on the public board being open to anyone who
 * reads the board, and one where that key gets you no further than the applicant queue. The old behaviour --
 * seating anyone who sent {@code "invited":true} -- is what {@link #aClaimWeNeverGrantedDoesNotAdmit} pins
 * down, and {@link #anAdmittedMemberIsSeatedBackAfterTheRoomIsRebuilt} pins down the case that makes the
 * grant worth keeping rather than consuming.
 */
class PartyAdmissionTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private PartyAdmissionService admissions;
	private PartyManager manager;
	private PartyFrameHandler handler;
	private CollectingSession host;
	private CollectingSession joiner;

	@BeforeEach
	void setUp() {
		NodeIdentity node = new NodeIdentity("node-a", true);
		manager = new PartyManager(mapper, new LocalPartyOwnershipService(node), node,
			new LocalPartyBus(), new LocalNodeLoadRegistry(), sessionId -> { }, 90_000L,
			new net.osparty.api.service.PlayerIdService("test-salt"));
		admissions = new LocalPartyAdmissionService();
		handler = new PartyFrameHandler(manager, mapper, admissions);
		host = new CollectingSession("host");
		joiner = new CollectingSession("joiner");
		handler.onOpen(host);
		handler.onOpen(joiner);
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":4}");
	}

	@Test
	void aGrantedSeatAdmitsTheJoiner() throws Exception {
		admissions.grant("r", "Mem");

		send(joiner, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");

		assertThat(statusOf(joiner)).isEqualTo("MEMBER");
	}

	@Test
	void aClaimWeNeverGrantedDoesNotAdmit() throws Exception {
		send(joiner, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");

		// Still in the party -- applying stays open to anyone, as it always was. What the claim no longer
		// buys is skipping the host's decision.
		assertThat(statusOf(joiner)).isEqualTo("PENDING");
	}

	@Test
	void aGrantForOneRoomDoesNotAdmitToAnother() throws Exception {
		admissions.grant("some-other-room", "Mem");

		send(joiner, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");

		assertThat(statusOf(joiner)).isEqualTo("PENDING");
	}

	@Test
	void aGrantIssuedToSomeoneElseDoesNotAdmit() throws Exception {
		admissions.grant("r", "SomeoneElse");

		send(joiner, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");

		assertThat(statusOf(joiner)).isEqualTo("PENDING");
	}

	/**
	 * The case that decides the shape of this service. A member the host admitted is in the party; when the
	 * room is rebuilt on another node it has no memory of that, and the member's own claim cannot be the
	 * thing that restores it. Without the grant, every handover tips the whole party into the applicant
	 * queue to be re-admitted one at a time.
	 */
	@Test
	void anAdmittedMemberIsSeatedBackAfterTheRoomIsRebuilt() throws Exception {
		send(joiner, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");
		assertThat(statusOf(joiner)).isEqualTo("PENDING");
		long memberId = welcome(joiner).get("m").asLong();
		send(host, "{\"t\":\"command\",\"action\":\"ADMIT\",\"target\":" + memberId + "}");

		// The room goes away with its node and is rebuilt from scratch by the returning host.
		handler.onClose(host.id(), "NORMAL");
		handler.onClose(joiner.id(), "NORMAL");
		manager.pruneRoom("r");
		CollectingSession hostAgain = new CollectingSession("host-again");
		CollectingSession joinerAgain = new CollectingSession("joiner-again");
		handler.onOpen(hostAgain);
		handler.onOpen(joinerAgain);
		send(hostAgain, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":4}");

		send(joinerAgain, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222,\"invited\":true}");

		assertThat(statusOf(joinerAgain)).isEqualTo("MEMBER");
	}

	/**
	 * The board invites by display name and the joiner reports whatever its client holds, so the two spell
	 * the same player differently. Both sides have to normalise, or a real grant fails to match itself.
	 */
	@Test
	void theGrantMatchesTheSameNameWrittenDifferently() throws Exception {
		admissions.grant("r", "Zezima Alt");

		send(joiner, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"zezima alt\",\"invited\":true}");

		assertThat(statusOf(joiner)).isEqualTo("MEMBER");
	}

	/**
	 * The other half of that: Jagex renders the space in a display name as U+00A0, so a grant addressed to a
	 * two-word name has to match the plain-spaced form the joiner sends back. Built from a code point rather
	 * than typed, because the two characters are indistinguishable on sight in a source file.
	 */
	@Test
	void aNonBreakingSpaceInANameIsTheSamePlayer() {
		admissions.grant("r", "Zezima" + Character.toString(160) + "Alt");

		assertThat(admissions.isGranted("r", "zezima alt")).isTrue();
	}

	/** The grant is what decides, so it admits a joiner that never thought to claim anything. */
	@Test
	void aJoinerNotClaimingAnythingIsStillAdmittedByAGrant() throws Exception {
		admissions.grant("r", "Mem");

		send(joiner, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\"}");

		assertThat(statusOf(joiner)).isEqualTo("MEMBER");
	}

	/**
	 * Room keys are passphrases and contain spaces, so a separator that can appear in either half would let
	 * a grant for one party admit someone to a differently-split other.
	 */
	@Test
	void roomAndNameCannotBeConfusedForOneAnother() {
		admissions.grant("alpha beta", "gamma");

		assertThat(admissions.isGranted("alpha", "beta gamma")).isFalse();
		assertThat(admissions.isGranted("alpha beta", "gamma")).isTrue();
	}

	/**
	 * An applicant is told the party's name and settings, and nothing about who is in it. The roster carries
	 * every member's account hash, and handing those to anyone who knows the room key -- which is on the
	 * public board -- gives away exactly what impersonating a member later would need.
	 */
	@Test
	void anApplicantIsNotShownWhoIsInTheParty() throws Exception {
		admissions.grant("r", "Mem");
		send(joiner, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222,\"invited\":true}");

		CollectingSession applicant = new CollectingSession("applicant");
		handler.onOpen(applicant);
		send(applicant, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Nosy\",\"accountHash\":333}");

		JsonNode roster = lastRoster(applicant);
		assertThat(roster.get("members")).hasSize(1);
		assertThat(roster.get("members").get(0).path("name").asText()).isEqualTo("Nosy");
		// Still enough to decide whether to wait: whose party it is, and how big.
		assertThat(roster.path("host").asText()).isEqualTo("Host");
		assertThat(roster.path("capacity").asInt()).isEqualTo(4);

		// The host, who is in the party, sees everyone.
		assertThat(lastRoster(host).get("members")).hasSize(3);
	}

	/** Admission is what opens the roster up, so a member sees the party the moment it is let in. */
	@Test
	void anAdmittedMemberIsShownTheWholeParty() throws Exception {
		send(joiner, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");
		assertThat(lastRoster(joiner).get("members")).hasSize(1);

		send(host, "{\"t\":\"command\",\"action\":\"ADMIT\",\"target\":" + welcome(joiner).get("m").asLong() + "}");

		assertThat(lastRoster(joiner).get("members")).hasSize(2);
	}

	private JsonNode lastRoster(CollectingSession session) throws Exception {
		JsonNode found = null;
		for (String json : session.out) {
			JsonNode node = mapper.readTree(json);
			if ("roster".equals(node.path("t").asText())) {
				found = node;
			}
		}
		if (found == null) {
			throw new AssertionError("no roster sent to " + session.id() + ": " + session.out);
		}
		return found;
	}

	private void send(CollectingSession session, String json) {
		handler.onMessage(session.id(), json.getBytes(StandardCharsets.UTF_8));
	}

	private String statusOf(CollectingSession session) throws Exception {
		return welcome(session).path("status").asText();
	}

	/** The seating decision, off the welcome frame. */
	private JsonNode welcome(CollectingSession session) throws Exception {
		for (String json : session.out) {
			JsonNode node = mapper.readTree(json);
			if ("welcome".equals(node.path("t").asText())) {
				return node;
			}
		}
		throw new AssertionError("never welcomed: " + session.out);
	}

	/** A connection with no transport under it; the frames it was sent are all a test here needs. */
	private static final class CollectingSession implements SocketSession {
		private final String id;
		private final List<String> out = new ArrayList<>();

		CollectingSession(String id) {
			this.id = id;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public boolean isOpen() {
			return true;
		}

		@Override
		public void send(byte[] frame) {
			out.add(new String(frame, StandardCharsets.UTF_8));
		}

		@Override
		public void sendText(String json) {
			out.add(json);
		}

		@Override
		public void close() {
		}

		@Override
		public boolean nodeHinted() {
			return false;
		}
	}
}

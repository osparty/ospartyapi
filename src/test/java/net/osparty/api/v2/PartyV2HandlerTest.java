package net.osparty.api.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Drives {@link PartyV2Handler} end-to-end with mock sessions to cover the P1 server core: host, join
 * (as a PENDING applicant), live-state relay, server-authoritative admission, and kick.
 */
class PartyV2HandlerTest {
	/** Long enough that no test trips the silence sweep by accident; staleness is driven explicitly. */
	private static final long MEMBER_TIMEOUT_MS = 90_000L;

	private final ObjectMapper mapper = new ObjectMapper();
	private PartyV2Handler handler;
	private PartyV2Manager manager;
	private LocalPartyV2Bus bus;

	private WebSocketSession host;
	private WebSocketSession member;
	private List<String> hostOut;
	private List<String> memberOut;

	@BeforeEach
	void setUp() {
		NodeIdentity node = new NodeIdentity("node-a", true);
		bus = new LocalPartyV2Bus();
		manager = new PartyV2Manager(mapper, new LocalPartyOwnershipService(node), node, bus, new LocalNodeLoadRegistry(), MEMBER_TIMEOUT_MS);
		handler = new PartyV2Handler(manager, mapper);
		hostOut = new ArrayList<>();
		memberOut = new ArrayList<>();
		host = session("host", hostOut);
		member = session("member", memberOut);
		handler.afterConnectionEstablished(host);
		handler.afterConnectionEstablished(member);
	}

	@Test
	void hostJoinRelayAdmitKick() throws Exception {
		send(host, "{\"type\":\"hello\",\"accountHash\":111,\"name\":\"Host\"}");
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":2}");

		JsonNode welcome = last(hostOut, "welcome");
		assertThat(welcome).isNotNull();
		assertThat(welcome.get("status").asText()).isEqualTo("HOST");
		// Names the node that actually owns the room, which placement may have moved off the one we dialled.
		assertThat(welcome.get("nodeId").asText()).isEqualTo("node-a");
		long hostId = welcome.get("memberId").asLong();
		assertThat(last(hostOut, "roster").get("members")).hasSize(1);

		// A joiner lands as PENDING; the host's roster now shows the applicant.
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");
		JsonNode memberWelcome = last(memberOut, "welcome");
		assertThat(memberWelcome.get("status").asText()).isEqualTo("PENDING");
		long memberId = memberWelcome.get("memberId").asLong();
		assertThat(memberId).isNotEqualTo(hostId);

		JsonNode hostRoster = last(hostOut, "roster");
		assertThat(hostRoster.get("members")).hasSize(2);
		assertThat(statusOf(hostRoster, memberId)).isEqualTo("PENDING");

		// A live snapshot from the host is relayed to the member (not echoed to the sender).
		int before = countOf(hostOut, "memberState");
		send(host, "{\"type\":\"state\",\"state\":{\"name\":\"Host\",\"world\":301,\"currentHp\":50}}");
		JsonNode memberState = last(memberOut, "memberState");
		assertThat(memberState).isNotNull();
		assertThat(memberState.get("memberId").asLong()).isEqualTo(hostId);
		assertThat(memberState.get("state").get("world").asInt()).isEqualTo(301);
		assertThat(countOf(hostOut, "memberState")).isEqualTo(before);

		// Host admits the applicant -> server-authoritative status flips to MEMBER for everyone.
		send(host, "{\"type\":\"command\",\"action\":\"ADMIT\",\"target\":" + memberId + "}");
		assertThat(statusOf(last(hostOut, "roster"), memberId)).isEqualTo("MEMBER");
		assertThat(statusOf(last(memberOut, "roster"), memberId)).isEqualTo("MEMBER");

		// Host kicks the member -> the target is told, and the roster drops back to the host alone.
		send(host, "{\"type\":\"command\",\"action\":\"KICK\",\"target\":" + memberId + "}");
		assertThat(last(memberOut, "kicked")).isNotNull();
		assertThat(last(hostOut, "roster").get("members")).hasSize(1);
	}

	/**
	 * The owner keeps no live state, so a joiner's baseline comes from the peers, not from a replay: seating
	 * someone asks the room to re-send. The joiner itself is skipped — it pushes its own state unprompted.
	 */
	@Test
	void seatingAJoinerAsksThePeersToResendTheirState() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		// Nobody to ask while the host is alone.
		assertThat(countOf(hostOut, "resync")).isZero();

		send(member, "{\"type\":\"join\",\"room\":\"r\"}");
		assertThat(countOf(hostOut, "resync")).isEqualTo(1);
		assertThat(countOf(memberOut, "resync")).isZero();
	}

	/** State sent before a joiner arrived is gone — the room never stored it. Only the resync brings it back. */
	@Test
	void aJoinerIsNotReplayedStateSentBeforeItArrived() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(host, "{\"type\":\"state\",\"state\":{\"name\":\"Host\",\"world\":301}}");

		send(member, "{\"type\":\"join\",\"room\":\"r\"}");
		assertThat(countOf(memberOut, "memberState")).isZero();

		// The host answers the resync and the joiner is caught up.
		send(host, "{\"type\":\"state\",\"state\":{\"name\":\"Host\",\"world\":301}}");
		assertThat(last(memberOut, "memberState").get("state").get("world").asInt()).isEqualTo(301);
	}

	/** A party re-forming seats several members at once; that must cost one round, not one round each. */
	@Test
	void resyncIsRateLimitedSoAWaveOfJoinersCostsOneRound() throws Exception {
		List<String> thirdOut = new ArrayList<>();
		WebSocketSession third = session("third", thirdOut);
		handler.afterConnectionEstablished(third);

		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":5}");
		send(member, "{\"type\":\"join\",\"room\":\"r\"}");
		send(third, "{\"type\":\"join\",\"room\":\"r\"}");

		assertThat(countOf(hostOut, "resync")).isEqualTo(1);
	}

	/**
	 * Once live frames are sent only on change, an idle member emits nothing — so the heartbeat is the only
	 * thing keeping its peers from timing it out. It reaches them, and is not echoed to the sender.
	 */
	@Test
	void heartbeatReachesPeersAsAliveAndIsNotEchoed() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"type\":\"join\",\"room\":\"r\"}");
		long memberId = last(memberOut, "welcome").get("memberId").asLong();

		send(member, "{\"type\":\"heartbeat\"}");

		JsonNode alive = last(hostOut, "alive");
		assertThat(alive).isNotNull();
		assertThat(alive.get("memberId").asLong()).isEqualTo(memberId);
		assertThat(countOf(memberOut, "alive")).isZero();
	}

	/**
	 * A live update is split by how often each part changes, and each part keeps its own type end to end.
	 * The room relays whichever arrived without knowing what any of them mean.
	 */
	@Test
	void splitUpdatesKeepTheirOwnTypesAndAreNotEchoed() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"type\":\"join\",\"room\":\"r\"}");
		long hostId = last(hostOut, "welcome").get("memberId").asLong();
		int echoed = countOf(hostOut, "memberVitals");

		send(host, "{\"type\":\"vitals\",\"state\":{\"currentHp\":31}}");
		send(host, "{\"type\":\"items\",\"state\":{\"inventory\":[995]}}");
		send(host, "{\"type\":\"profile\",\"state\":{\"name\":\"Host\",\"world\":301}}");

		assertThat(last(memberOut, "memberVitals").get("state").get("currentHp").asInt()).isEqualTo(31);
		assertThat(last(memberOut, "memberItems").get("state").get("inventory")).hasSize(1);
		assertThat(last(memberOut, "memberProfile").get("state").get("world").asInt()).isEqualTo(301);
		assertThat(last(memberOut, "memberVitals").get("memberId").asLong()).isEqualTo(hostId);
		// Still never echoed to the sender.
		assertThat(countOf(hostOut, "memberVitals")).isEqualTo(echoed);
	}

	@Test
	void aLaterHelloFillsInAnAlreadySeatedMembersIdentity() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		// A joiner doesn't know its own name yet when the UI sends the join frame.
		send(member, "{\"type\":\"join\",\"room\":\"r\"}");
		long memberId = last(memberOut, "welcome").get("memberId").asLong();
		assertThat(nameOf(last(hostOut, "roster"), memberId)).isNull();

		send(member, "{\"type\":\"hello\",\"name\":\"Mem\",\"accountHash\":222}");

		JsonNode roster = last(hostOut, "roster");
		assertThat(nameOf(roster, memberId)).isEqualTo("Mem");
		assertThat(accountHashOf(roster, memberId)).isEqualTo(222);
	}

	@Test
	void aLaterHelloFromTheHostRenamesTheRoom() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"capacity\":3}");
		send(host, "{\"type\":\"hello\",\"name\":\"Host\",\"accountHash\":111}");

		assertThat(last(hostOut, "roster").get("host").asText()).isEqualTo("Host");
	}

	@Test
	void losingOwnershipFencesAuthoritativeActionsAndDrainsTheRoom() throws Exception {
		NodeIdentity node = new NodeIdentity("node-a", true);
		// Ownership that is granted on claim and then yanked away, as an expired lock would be.
		AtomicBoolean owned = new AtomicBoolean(true);
		PartyOwnershipService flaky = new PartyOwnershipService() {
			public Claim claim(String room) {
				return Claim.CLAIMED;
			}

			public java.util.Optional<Owner> lookup(String room) {
				return java.util.Optional.of(new Owner("node-a"));
			}

			public boolean renew(String room) {
				return owned.get();
			}

			public boolean ownedBySelf(String room) {
				return owned.get();
			}

			public void release(String room) {
			}

			public void releaseForHandover(String room) {
			}

			public boolean handoverPending(String room) {
				return false;
			}

			public java.util.Set<String> reclaimExpired() {
				return java.util.Set.of();
			}
		};
		PartyV2Manager manager = new PartyV2Manager(mapper, flaky, node, new LocalPartyV2Bus(), new LocalNodeLoadRegistry(), MEMBER_TIMEOUT_MS);
		PartyV2Handler fenced = new PartyV2Handler(manager, mapper);
		fenced.afterConnectionEstablished(host);
		fenced.afterConnectionEstablished(member);
		fenced.handleTextMessage(host, new TextMessage(
			"{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));
		fenced.handleTextMessage(member, new TextMessage(
			"{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}"));
		long memberId = last(memberOut, "welcome").get("memberId").asLong();

		owned.set(false);
		fenced.handleTextMessage(host, new TextMessage(
			"{\"type\":\"command\",\"action\":\"KICK\",\"target\":" + memberId + "}"));

		// The kick is refused, and everyone is told to reconnect elsewhere.
		assertThat(last(memberOut, "kicked")).isNull();
		assertThat(last(memberOut, "ownerChanged")).isNotNull();
		assertThat(last(hostOut, "ownerChanged")).isNotNull();
		assertThat(manager.roomCount()).isZero();
		assertThat(manager.failoverCount()).isEqualTo(1);
	}

	@Test
	void joiningUnknownRoomErrors() throws Exception {
		send(member, "{\"type\":\"join\",\"room\":\"nope\"}");
		assertThat(last(memberOut, "error").get("detail").asText()).isEqualTo("no room");
		// Terminal, not retriable: the retry path must not swallow a room that never existed.
		assertThat(last(memberOut, "ownerPending")).isNull();
	}

	@Test
	void joiningRoomOwnedElsewhereRedirects() throws Exception {
		// A room this node does not host but another node owns -> the joiner is sent to that owner.
		NodeIdentity node = new NodeIdentity("node-a", true);
		PartyOwnershipService foreign = new PartyOwnershipService() {
			public Claim claim(String room) {
				return Claim.OWNED_BY_OTHER;
			}

			public java.util.Optional<Owner> lookup(String room) {
				return java.util.Optional.of(new Owner("node-b"));
			}

			public boolean renew(String room) {
				return false;
			}

			public boolean ownedBySelf(String room) {
				return false;
			}

			public void release(String room) {
			}

			public void releaseForHandover(String room) {
			}

			public boolean handoverPending(String room) {
				return false;
			}

			public java.util.Set<String> reclaimExpired() {
				return java.util.Set.of();
			}
		};
		PartyV2Handler redirecting = new PartyV2Handler(
			new PartyV2Manager(mapper, foreign, node, new LocalPartyV2Bus(), new LocalNodeLoadRegistry(), MEMBER_TIMEOUT_MS), mapper);
		List<String> out = new ArrayList<>();
		WebSocketSession joiner = session("joiner", out);
		redirecting.afterConnectionEstablished(joiner);

		redirecting.handleTextMessage(joiner, new TextMessage("{\"type\":\"join\",\"room\":\"elsewhere\"}"));

		assertThat(last(out, "redirect").get("nodeId").asText()).isEqualTo("node-b");
	}

	@Test
	void hostingOnALoadedNodeIsSentToALighterOne() throws Exception {
		NodeIdentity node = new NodeIdentity("node-a", true);
		java.util.concurrent.atomic.AtomicReference<String> lighter =
			new java.util.concurrent.atomic.AtomicReference<>("node-b");
		NodeLoadRegistry load = new NodeLoadRegistry() {
			public void publish(int members) {
			}

			public void retire() {
			}

			public java.util.Optional<String> preferredHost(int selfMembers) {
				return java.util.Optional.ofNullable(lighter.get());
			}
		};
		PartyV2Manager loaded = new PartyV2Manager(
			mapper, new LocalPartyOwnershipService(node), node, new LocalPartyV2Bus(), load,
			MEMBER_TIMEOUT_MS);
		PartyV2Handler placing = new PartyV2Handler(loaded, mapper);
		List<String> out = new ArrayList<>();
		WebSocketSession newHost = session("newHost", out);
		placing.afterConnectionEstablished(newHost);

		placing.handleTextMessage(newHost, new TextMessage(
			"{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));

		// Redirected rather than claimed: nothing is hosted here, and the client will re-send host there.
		assertThat(last(out, "redirect").get("nodeId").asText()).isEqualTo("node-b");
		assertThat(last(out, "welcome")).isNull();
		assertThat(loaded.roomCount()).isZero();
		assertThat(loaded.rebalanceCount()).isEqualTo(1);

		// Once the room exists here, load no longer decides: a host re-sending its frame after a reconnect
		// must land back on its own room rather than being bounced to whichever node is lightest today.
		lighter.set(null);
		placing.handleTextMessage(newHost, new TextMessage(
			"{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));
		assertThat(last(out, "welcome").get("status").asText()).isEqualTo("HOST");
		assertThat(loaded.roomCount()).isEqualTo(1);

		lighter.set("node-b");
		out.clear();
		placing.handleTextMessage(newHost, new TextMessage(
			"{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));
		assertThat(last(out, "redirect")).isNull();
		assertThat(loaded.rebalanceCount()).isEqualTo(1);

		// A host that arrived on the node-hint path was sent here on purpose. Re-placing it would let two
		// nodes with slightly different load snapshots bounce the same client between them.
		List<String> hintedOut = new ArrayList<>();
		WebSocketSession hinted = session("hinted", hintedOut);
		when(hinted.getUri()).thenReturn(java.net.URI.create("/n/node-a/api/v2/ws/party"));
		placing.afterConnectionEstablished(hinted);
		placing.handleTextMessage(hinted, new TextMessage(
			"{\"type\":\"host\",\"room\":\"r2\",\"hostName\":\"Other\",\"capacity\":3}"));

		assertThat(last(hintedOut, "redirect")).isNull();
		assertThat(last(hintedOut, "welcome").get("status").asText()).isEqualTo("HOST");
		assertThat(loaded.rebalanceCount()).isEqualTo(1);
	}

	@Test
	void sweepingDropsMembersWhoseSocketDiedWithoutACloseCallback() throws Exception {
		long memberId = hostWithAdmittedMember();
		assertThat(manager.connectedMembers()).isEqualTo(2);

		// The member's socket dies without afterConnectionClosed ever firing. Sends to it are silently
		// skipped, so nothing else in the system would ever notice it had gone.
		when(member.isOpen()).thenReturn(false);
		manager.pruneRoom("r");

		assertThat(manager.connectedMembers()).isEqualTo(1);
		assertThat(manager.prunedCount()).isEqualTo(1);
		// The host hears about it exactly as it would for a clean leave.
		assertThat(last(hostOut, "memberLeft").get("memberId").asLong()).isEqualTo(memberId);
		// The room survives — its host is still connected.
		assertThat(manager.roomCount()).isEqualTo(1);

		// When the host goes the same way the room goes with it, releasing its ownership lock instead of
		// being renewed forever.
		when(host.isOpen()).thenReturn(false);
		manager.pruneRoom("r");
		assertThat(manager.roomCount()).isZero();
		assertThat(manager.prunedCount()).isEqualTo(2);
	}

	@Test
	void sweepingDropsAMemberThatWentSilentWhileItsSocketStillLooksOpen() throws Exception {
		// The case isOpen() cannot see: the client is gone but nothing closed the connection, so the session
		// reports open indefinitely and the room never empties. Traffic is the only honest signal.
		PartyV2Manager impatient = new PartyV2Manager(
			mapper, new LocalPartyOwnershipService(new NodeIdentity("node-a", true)),
			// -1 rather than 0: with a zero timeout a member stamped in the same millisecond as the sweep is
			// not yet stale, which makes the assertion depend on the clock. Negative means "everything is".
			new NodeIdentity("node-a", true), new LocalPartyV2Bus(), new LocalNodeLoadRegistry(), -1L);
		PartyV2Handler sweeping = new PartyV2Handler(impatient, mapper);
		sweeping.afterConnectionEstablished(host);
		sweeping.afterConnectionEstablished(member);
		sweeping.handleTextMessage(host, new TextMessage(
			"{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}"));
		sweeping.handleTextMessage(member, new TextMessage(
			"{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}"));
		assertThat(impatient.connectedMembers()).isEqualTo(2);

		// Both sessions still report open — only silence gives them away.
		assertThat(host.isOpen()).isTrue();
		assertThat(member.isOpen()).isTrue();
		impatient.pruneRoom("r");

		assertThat(impatient.prunedCount()).isEqualTo(2);
		assertThat(impatient.roomCount()).isZero();
	}

	@Test
	void sweepingLeavesALiveRoomAlone() throws Exception {
		hostWithAdmittedMember();
		manager.pruneRoom("r");
		// Nothing closed, so nothing removed — and no discard, which would otherwise be free to release the
		// lock of a room created microseconds ago whose host is still being seated.
		assertThat(manager.prunedCount()).isZero();
		assertThat(manager.roomCount()).isEqualTo(1);
		assertThat(manager.connectedMembers()).isEqualTo(2);
	}

	@Test
	void readyChecksAndSpecDrainsFanOutToPeers() throws Exception {
		long memberId = hostWithAdmittedMember();

		send(host, "{\"type\":\"readyStart\",\"checkId\":7,\"starter\":\"Host\"}");
		JsonNode start = last(memberOut, "readyStart");
		assertThat(start.get("checkId").asLong()).isEqualTo(7);
		assertThat(start.get("starter").asText()).isEqualTo("Host");
		// The sender doesn't receive its own broadcast (it applies the check locally).
		assertThat(last(hostOut, "readyStart")).isNull();

		send(member, "{\"type\":\"ready\",\"checkId\":7}");
		assertThat(last(hostOut, "ready").get("memberId").asLong()).isEqualTo(memberId);

		send(member, "{\"type\":\"specDrain\",\"npcIndex\":42,\"weapon\":\"DRAGON_WARHAMMER\",\"hit\":25,"
			+ "\"world\":301}");
		JsonNode drain = last(hostOut, "specDrain");
		assertThat(drain.get("npcIndex").asInt()).isEqualTo(42);
		assertThat(drain.get("weapon").asText()).isEqualTo("DRAGON_WARHAMMER");
		assertThat(drain.get("hit").asInt()).isEqualTo(25);
	}

	@Test
	void pendingApplicantCannotFanOutReadyOrSpec() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":2}");
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\"}"); // stays PENDING

		send(member, "{\"type\":\"readyStart\",\"checkId\":1,\"starter\":\"Mem\"}");
		send(member, "{\"type\":\"specDrain\",\"npcIndex\":1,\"weapon\":\"BANDOS_GODSWORD\",\"hit\":10}");

		assertThat(last(hostOut, "readyStart")).isNull();
		assertThat(last(hostOut, "specDrain")).isNull();
	}

	@Test
	void fcRequestReachesOnlyTheTargetAndOnlyFromTheHost() throws Exception {
		long memberId = hostWithAdmittedMember();

		send(host, "{\"type\":\"fcRequest\",\"target\":" + memberId + ",\"kind\":\"FC\",\"friendsChat\":\"Zuk\"}");
		JsonNode request = last(memberOut, "fcRequest");
		assertThat(request.get("kind").asText()).isEqualTo("FC");
		assertThat(request.get("friendsChat").asText()).isEqualTo("Zuk");
		assertThat(request.get("host").asText()).isEqualTo("Host");

		// A member cannot send prompts back at the host.
		send(member, "{\"type\":\"fcRequest\",\"target\":1,\"kind\":\"FC\",\"friendsChat\":\"nope\"}");
		assertThat(last(hostOut, "fcRequest")).isNull();
	}

	/**
	 * The host's ad settings reach members that are already seated and members that arrive later — the
	 * latter matters most, since a joiner's own copy of the ad is a snapshot of whatever the search board
	 * showed it.
	 */
	@Test
	void hostAdMetaReachesSeatedMembersAndLaterJoiners() throws Exception {
		hostWithAdmittedMember();

		send(host, "{\"type\":\"setMeta\",\"meta\":{\"host\":\"Host\",\"world\":\"301\",\"lootRule\":\"FFA\"}}");
		JsonNode meta = last(memberOut, "meta");
		assertThat(meta.get("meta").get("world").asText()).isEqualTo("301");
		assertThat(meta.get("meta").get("lootRule").asText()).isEqualTo("FFA");
		// Relayed verbatim: the server stores the payload without interpreting it.
		assertThat(last(hostOut, "meta")).isNull();

		// A member cannot rewrite the ad settings under the host.
		send(member, "{\"type\":\"setMeta\",\"meta\":{\"host\":\"Mem\",\"world\":\"999\"}}");
		assertThat(last(hostOut, "meta")).isNull();
		assertThat(last(memberOut, "meta").get("meta").get("world").asText()).isEqualTo("301");

		// A later joiner gets the current settings in its seating snapshot.
		List<String> lateOut = new ArrayList<>();
		WebSocketSession late = session("late", lateOut);
		handler.afterConnectionEstablished(late);
		send(late, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Late\",\"invited\":true}");
		assertThat(last(lateOut, "meta").get("meta").get("lootRule").asText()).isEqualTo("FFA");
	}

	@Test
	void hostTransferCommitMovesAuthoritativeHostStatus() throws Exception {
		long memberId = hostWithAdmittedMember();
		long hostId = last(hostOut, "welcome").get("memberId").asLong();

		send(host, "{\"type\":\"transferHost\",\"kind\":\"OFFER\",\"target\":" + memberId
			+ ",\"newHostKey\":\"k\",\"newHostName\":\"Mem\",\"hostStays\":true}");
		assertThat(last(memberOut, "transferHost").get("kind").asText()).isEqualTo("OFFER");

		// ACCEPT is the one member-initiated step, addressed back at the host.
		send(member, "{\"type\":\"transferHost\",\"kind\":\"ACCEPT\",\"target\":" + hostId + "}");
		assertThat(last(hostOut, "transferHost").get("kind").asText()).isEqualTo("ACCEPT");

		send(host, "{\"type\":\"transferHost\",\"kind\":\"COMMIT\",\"target\":" + memberId
			+ ",\"newHostKey\":\"k\",\"hostStays\":true}");
		JsonNode roster = last(memberOut, "roster");
		assertThat(statusOf(roster, memberId)).isEqualTo("HOST");
		assertThat(statusOf(roster, hostId)).isEqualTo("MEMBER");
		assertThat(roster.get("host").asText()).isEqualTo("Mem");
	}

	@Test
	void hostTransferCommitDropsTheOldHostWhenItDoesNotStay() throws Exception {
		long memberId = hostWithAdmittedMember();
		long hostId = last(hostOut, "welcome").get("memberId").asLong();

		send(host, "{\"type\":\"transferHost\",\"kind\":\"COMMIT\",\"target\":" + memberId
			+ ",\"hostStays\":false}");

		JsonNode roster = last(memberOut, "roster");
		assertThat(roster.get("members")).hasSize(1);
		assertThat(statusOf(roster, memberId)).isEqualTo("HOST");
		assertThat(statusOf(roster, hostId)).isNull();
	}

	/**
	 * A dead peer must not take a healthy one down with it. Tomcat runs a failed send's close inline on the
	 * sending thread, which re-enters onLeave and drops that member from the room mid-fan-out; iterating the
	 * live session map there threw a ConcurrentModificationException out of the frame handler, killing the
	 * sender's own session. Under load — every member broadcasting state each tick — that cascades.
	 */
	@Test
	void aSendThatClosesItsOwnSessionDoesNotBreakTheFanOut() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":4}");

		// Seated between the host and a healthy member, so the fan-out still has someone to visit after the
		// removal — which is exactly when the iterator noticed and threw.
		List<String> deadOut = new ArrayList<>();
		WebSocketSession dead = session("dead", deadOut);
		handler.afterConnectionEstablished(dead);
		send(dead, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Dead\",\"invited\":true}");
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		long memberId = last(memberOut, "welcome").get("memberId").asLong();

		// The peer's connection is gone: writing to it fails, and the container closes it on our thread.
		doAnswer(inv -> {
			handler.afterConnectionClosed(dead, org.springframework.web.socket.CloseStatus.SESSION_NOT_RELIABLE);
			throw new java.io.IOException("broken pipe");
		}).when(dead).sendMessage(any());

		send(host, "{\"type\":\"state\",\"state\":{\"name\":\"Host\",\"world\":301}}");

		// The healthy member still got the frame, and the room dropped only the dead peer.
		assertThat(last(memberOut, "memberState").get("state").get("world").asInt()).isEqualTo(301);
		assertThat(last(memberOut, "roster").get("members")).hasSize(2);
		assertThat(statusOf(last(memberOut, "roster"), memberId)).isEqualTo("MEMBER");
	}

	/** The same hazard on the leave path: onLeave broadcasts, and a dead peer removes itself mid-fan-out. */
	@Test
	void aDeadPeerDoesNotBreakTheFanOutOfSomeoneElseLeaving() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":6}");

		List<String> deadOut = new ArrayList<>();
		WebSocketSession dead = session("dead", deadOut);
		List<String> leaverOut = new ArrayList<>();
		WebSocketSession leaver = session("leaver", leaverOut);
		handler.afterConnectionEstablished(dead);
		handler.afterConnectionEstablished(leaver);
		// Seated so the healthy member comes after the dead one in the fan-out, which is when it mattered.
		send(dead, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Dead\",\"invited\":true}");
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		send(leaver, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Leaver\",\"invited\":true}");
		long leaverId = last(leaverOut, "welcome").get("memberId").asLong();

		doAnswer(inv -> {
			handler.afterConnectionClosed(dead, org.springframework.web.socket.CloseStatus.SESSION_NOT_RELIABLE);
			throw new java.io.IOException("broken pipe");
		}).when(dead).sendMessage(any());

		send(leaver, "{\"type\":\"leave\"}");

		assertThat(last(memberOut, "memberLeft").get("memberId").asLong()).isEqualTo(leaverId);
		// Host and the healthy member remain; the leaver and the dead peer are both gone.
		assertThat(last(memberOut, "roster").get("members")).hasSize(2);
	}

	@Test
	void hostLeavingClosesTheRoomForMembers() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		handler.afterConnectionClosed(host, org.springframework.web.socket.CloseStatus.NORMAL);

		JsonNode roster = last(memberOut, "roster");
		assertThat(roster.get("closed").asBoolean()).isTrue();
	}

	// ---- helpers ------------------------------------------------------------

	/** Host a room and admit one member (an invited joiner is seated straight away); returns its id. */
	private long hostWithAdmittedMember() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		return last(memberOut, "welcome").get("memberId").asLong();
	}

	/**
	 * The drain race: a member reconnects after its owner node drained, but before the host has re-hosted
	 * the room on its new node. It must be told to retry — answering "no room" strands it in a party whose
	 * host it can never see again, which is the failure this whole handover path exists to prevent.
	 */
	@Test
	void joinDuringHandoverIsRetriableNotTerminal() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":4}");
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");

		// The owning node drains on shutdown: everyone is told, the lock is handed over.
		manager.drain("r", true);
		assertThat(last(memberOut, "ownerChanged")).isNotNull();

		// The member reconnects and re-sends its join before the host has re-claimed the room.
		memberOut.clear();
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");

		JsonNode pending = last(memberOut, "ownerPending");
		assertThat(pending).isNotNull();
		assertThat(pending.get("retryAfterMs").asLong()).isPositive();
		assertThat(last(memberOut, "error")).isNull();

		// Once the host re-hosts, the retry seats the member for real.
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":4}");
		memberOut.clear();
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222,\"invited\":true}");
		assertThat(last(memberOut, "welcome")).isNotNull();
		assertThat(last(memberOut, "welcome").get("status").asText()).isEqualTo("MEMBER");
	}

	/**
	 * A room whose owner died is taken over by the scan, and the takeover settles where everyone goes: the
	 * reclaiming node holds the lock, defers joiners until the host rebuilds the room, then seats them.
	 */
	@Test
	void reclaimTakesOverAnExpiredRoomAndWaitsForItsHost() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":4}");
		// The owner goes away without ending the party.
		manager.drain("r", true);
		assertThat(manager.roomCount()).isZero();

		assertThat(manager.reclaimExpired()).containsExactly("r");
		assertThat(manager.reclaimCount()).isEqualTo(1);
		// The lock is held here, but the room itself only comes back with its host.
		assertThat(manager.owner("r")).map(PartyOwnershipService.Owner::nodeId).contains("node-a");
		assertThat(manager.roomCount()).isZero();

		// A joiner reaching the new owner before the host must be deferred, never redirected to this very
		// node — a self-redirect is a dead end the client would ignore.
		memberOut.clear();
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\"}");
		assertThat(last(memberOut, "ownerPending")).isNotNull();
		assertThat(last(memberOut, "redirect")).isNull();
		assertThat(last(memberOut, "error")).isNull();

		// The host returns and rebuilds the room on the node that reclaimed it.
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":4}");
		memberOut.clear();
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		assertThat(last(memberOut, "welcome").get("status").asText()).isEqualTo("MEMBER");
	}

	/** A claim announced by another node drops this node's copy of the room immediately. */
	@Test
	void busOwnerChangedDrainsARoomWeNoLongerOwn() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		assertThat(manager.roomCount()).isEqualTo(1);

		bus.listener().onOwnerChanged("r", "node-b");

		assertThat(manager.roomCount()).isZero();
		assertThat(manager.failoverCount()).isEqualTo(1);
		assertThat(last(hostOut, "ownerChanged")).isNotNull();
		assertThat(last(memberOut, "ownerChanged")).isNotNull();
	}

	/** Signals about rooms this node does not serve are other nodes' business. */
	@Test
	void busIgnoresSignalsForRoomsWeDoNotServe() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");

		bus.listener().onOwnerChanged("someone-elses-room", "node-b");
		bus.listener().onForceReconnect("someone-elses-room");

		assertThat(manager.roomCount()).isEqualTo(1);
		assertThat(manager.failoverCount()).isZero();
	}

	/** Force-reconnect hands the room over: members are sent off and the lock is released for the taking. */
	@Test
	void busForceReconnectDrainsAndReleasesTheRoom() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");

		bus.listener().onForceReconnect("r");

		assertThat(manager.roomCount()).isZero();
		assertThat(last(memberOut, "ownerChanged")).isNotNull();
		// Released for handover, so the room is claimable again rather than simply gone.
		assertThat(manager.owner("r")).isEmpty();
		assertThat(manager.handoverPending("r")).isTrue();
	}

	/** A room that ended (host left, room discarded) is gone, not in transit. */
	@Test
	void joinAfterRoomEndedErrors() throws Exception {
		send(host, "{\"type\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\"}");
		manager.discard("r");

		memberOut.clear();
		send(member, "{\"type\":\"join\",\"room\":\"r\",\"name\":\"Mem\"}");
		assertThat(last(memberOut, "ownerPending")).isNull();
		assertThat(last(memberOut, "error").get("detail").asText()).isEqualTo("no room");
	}

	private void send(WebSocketSession session, String json) throws Exception {
		handler.handleTextMessage(session, new TextMessage(json));
	}

	private JsonNode last(List<String> out, String type) throws Exception {
		JsonNode found = null;
		for (String json : out) {
			JsonNode node = mapper.readTree(json);
			if (type.equals(node.path("type").asText())) {
				found = node;
			}
		}
		return found;
	}

	private int countOf(List<String> out, String type) throws Exception {
		int n = 0;
		for (String json : out) {
			if (type.equals(mapper.readTree(json).path("type").asText())) {
				n++;
			}
		}
		return n;
	}

	private static String nameOf(JsonNode roster, long memberId) {
		for (JsonNode m : roster.get("members")) {
			if (m.get("memberId").asLong() == memberId) {
				return m.hasNonNull("name") ? m.get("name").asText() : null;
			}
		}
		return null;
	}

	private static long accountHashOf(JsonNode roster, long memberId) {
		for (JsonNode m : roster.get("members")) {
			if (m.get("memberId").asLong() == memberId) {
				return m.get("accountHash").asLong();
			}
		}
		return 0;
	}

	private static String statusOf(JsonNode roster, long memberId) {
		for (JsonNode m : roster.get("members")) {
			if (m.get("memberId").asLong() == memberId) {
				return m.get("status").asText();
			}
		}
		return null;
	}

	private static WebSocketSession session(String id, List<String> out) {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getId()).thenReturn(id);
		when(session.isOpen()).thenReturn(true);
		try {
			doAnswer(inv -> {
				out.add(((TextMessage) inv.getArgument(0)).getPayload());
				return null;
			}).when(session).sendMessage(any());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		return session;
	}
}

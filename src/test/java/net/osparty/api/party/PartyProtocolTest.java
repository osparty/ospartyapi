package net.osparty.api.party;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.osparty.api.transport.SocketSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link PartyFrameHandler} end-to-end with fake sessions to cover the server core: host, join
 * (as a PENDING applicant), live-state relay, server-authoritative admission, and kick.
 *
 * <p>Against the frame handler rather than a transport, because the protocol is where all of this lives —
 * what carries the bytes only has to call {@code onOpen}, {@code onMessage} and {@code onClose}.
 */
class PartyProtocolTest {
	/** Long enough that no test trips the silence sweep by accident; staleness is driven explicitly. */
	private static final long MEMBER_TIMEOUT_MS = 90_000L;

	/** The room every test here hosts in. */
	private static final String ROOM = "r";

	private final ObjectMapper mapper = new ObjectMapper();
	private PartyFrameHandler handler;
	private PartyManager manager;
	private LocalPartyBus bus;
	private List<String> adsDropped;
	/**
	 * Auto-admission is decided from this, never from the joiner's own {@code invited} flag, so a test that
	 * wants a member seated in one frame says so by granting the seat first (see {@link #inviteFor}).
	 * {@link PartyAdmissionTest} covers the rule itself.
	 */
	private LocalPartyAdmissionService invites;

	private FakeSession host;
	private FakeSession member;
	private List<String> hostOut;
	private List<String> memberOut;

	@BeforeEach
	void setUp() {
		NodeIdentity node = new NodeIdentity("node-a", true);
		bus = new LocalPartyBus();
		adsDropped = new ArrayList<>();
		manager = new PartyManager(mapper, new LocalPartyOwnershipService(node), node, bus, new LocalNodeLoadRegistry(), adsDropped::add, MEMBER_TIMEOUT_MS);
		invites = new LocalPartyAdmissionService();
		handler = new PartyFrameHandler(manager, mapper, invites);
		hostOut = new ArrayList<>();
		memberOut = new ArrayList<>();
		host = session("host", hostOut);
		member = session("member", memberOut);
		handler.onOpen(host);
		handler.onOpen(member);
	}

	@Test
	void hostJoinRelayAdmitKick() throws Exception {
		send(host, "{\"t\":\"hello\",\"accountHash\":111,\"name\":\"Host\"}");
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":2}");

		JsonNode welcome = last(hostOut, "welcome");
		assertThat(welcome).isNotNull();
		assertThat(welcome.get("status").asText()).isEqualTo("HOST");
		// Names the node that actually owns the room, which placement may have moved off the one we dialled.
		assertThat(welcome.get("nodeId").asText()).isEqualTo("node-a");
		long hostId = welcome.get("m").asLong();
		assertThat(last(hostOut, "roster").get("members")).hasSize(1);

		// A joiner lands as PENDING; the host's roster now shows the applicant.
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");
		JsonNode memberWelcome = last(memberOut, "welcome");
		assertThat(memberWelcome.get("status").asText()).isEqualTo("PENDING");
		long memberId = memberWelcome.get("m").asLong();
		assertThat(memberId).isNotEqualTo(hostId);

		JsonNode hostRoster = last(hostOut, "roster");
		assertThat(hostRoster.get("members")).hasSize(2);
		assertThat(statusOf(hostRoster, memberId)).isEqualTo("PENDING");

		// A live update from the host reaches the member on the next flush.
		send(host, "{\"t\":\"update\",\"s\":{\"name\":\"Host\",\"world\":301,\"currentHp\":50}}");
		manager.flushRooms();
		JsonNode relayed = onlyUpdate(last(memberOut, "mu"));
		assertThat(relayed).isNotNull();
		assertThat(relayed.get("m").asLong()).isEqualTo(hostId);
		assertThat(relayed.get("s").get("world").asInt()).isEqualTo(301);

		// Host admits the applicant -> server-authoritative status flips to MEMBER for everyone.
		send(host, "{\"t\":\"command\",\"action\":\"ADMIT\",\"target\":" + memberId + "}");
		assertThat(statusOf(last(hostOut, "roster"), memberId)).isEqualTo("MEMBER");
		assertThat(statusOf(last(memberOut, "roster"), memberId)).isEqualTo("MEMBER");

		// Host kicks the member -> the target is told, and the roster drops back to the host alone.
		send(host, "{\"t\":\"command\",\"action\":\"KICK\",\"target\":" + memberId + "}");
		assertThat(last(memberOut, "kicked")).isNotNull();
		assertThat(last(hostOut, "roster").get("members")).hasSize(1);
	}

	/**
	 * The owner keeps no live state, so a joiner's baseline comes from the peers, not from a replay: seating
	 * someone asks the room to re-send, on the next sweep.
	 */
	@Test
	void seatingAJoinerAsksThePeersToResendTheirState() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		// Nobody to ask while the host is alone.
		manager.flushRooms();
		assertThat(countOf(hostOut, "resync")).isZero();

		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		manager.flushRooms();
		assertThat(countOf(hostOut, "resync")).isEqualTo(1);
		// The joiner is excused its own round — it pushes its state unprompted.
		assertThat(countOf(memberOut, "resync")).isZero();
	}

	/** Owed once per wave, not once per sweep: a room with nobody newly seated asks for nothing. */
	@Test
	void aSettledRoomAsksForNoFurtherResyncs() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		manager.flushRooms();
		manager.flushRooms();
		manager.flushRooms();

		assertThat(countOf(hostOut, "resync")).isEqualTo(1);
	}

	/** State sent before a joiner arrived is gone — the room never stored it. Only the resync brings it back. */
	@Test
	void aJoinerIsNotReplayedStateSentBeforeItArrived() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(host, "{\"t\":\"update\",\"s\":{\"name\":\"Host\",\"world\":301}}");
		manager.flushRooms();

		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		assertThat(countOf(memberOut, "mu")).isZero();

		// The host answers the resync and the joiner is caught up.
		send(host, "{\"t\":\"update\",\"s\":{\"name\":\"Host\",\"world\":301}}");
		manager.flushRooms();
		assertThat(onlyUpdate(last(memberOut, "mu")).get("s").get("world").asInt()).isEqualTo(301);
	}

	/**
	 * The point of aggregating: a window's updates from several members become <em>one</em> send each, not one
	 * send per sender per recipient. With three members that is 3 sends instead of 6, and the saving grows
	 * with the square of the party.
	 *
	 * <p>Each recipient's copy omits its own updates. Sharing one frame with the whole room was tried and
	 * measured worse — same information, 12% more frames, no CPU saved.
	 */
	@Test
	void aWindowOfUpdatesCostsOneSendPerMember() throws Exception {
		List<String> thirdOut = new ArrayList<>();
		FakeSession third = session("third", thirdOut);
		handler.onOpen(third);

		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":5}");
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		send(third, "{\"t\":\"join\",\"room\":\"r\"}");
		int hostBefore = countOf(hostOut, "mu");
		int memberBefore = countOf(memberOut, "mu");

		send(host, "{\"t\":\"update\",\"s\":{\"currentHp\":10}}");
		send(member, "{\"t\":\"update\",\"s\":{\"currentHp\":20}}");
		send(third, "{\"t\":\"update\",\"s\":{\"currentHp\":30}}");
		manager.flushRooms();

		// One frame each, not one per peer.
		assertThat(countOf(hostOut, "mu")).isEqualTo(hostBefore + 1);
		assertThat(countOf(memberOut, "mu")).isEqualTo(memberBefore + 1);
		// And each carries the other two, never its own.
		JsonNode hostFrame = last(hostOut, "mu").get("u");
		assertThat(hostFrame).hasSize(2);
		long hostId = last(hostOut, "welcome").get("m").asLong();
		for (JsonNode update : hostFrame) {
			assertThat(update.get("m").asLong()).isNotEqualTo(hostId);
		}
	}

	/** Nothing queued means nothing sent — the flush must not wake a quiet room. */
	@Test
	void flushingAQuietRoomSendsNothing() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		int before = memberOut.size();

		manager.flushRooms();
		manager.flushRooms();

		assertThat(memberOut).hasSize(before);
	}

	/** A party re-forming seats several members at once; that must cost one round, not one round each. */
	@Test
	void aWaveOfJoinersCostsOneResyncRound() throws Exception {
		List<String> thirdOut = new ArrayList<>();
		FakeSession third = session("third", thirdOut);
		handler.onOpen(third);

		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":5}");
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		send(third, "{\"t\":\"join\",\"room\":\"r\"}");
		manager.flushRooms();

		assertThat(countOf(hostOut, "resync")).isEqualTo(1);
	}

	/**
	 * The second of two joiners is owed a round of its own once the first one's has already been answered.
	 * Coalescing a wave into one round is only sound while the wave is still gathering; past that, the round
	 * that went out is spent, and suppressing the next one strands the second joiner with no live state at
	 * all — peers offline and blank, until one of them happens to change.
	 */
	@Test
	void aJoinerSeatedAfterTheLastRoundWasAnsweredGetsOneOfItsOwn() throws Exception {
		List<String> thirdOut = new ArrayList<>();
		FakeSession third = session("third", thirdOut);
		handler.onOpen(third);

		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":5}");
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		// The first joiner's round goes out and the peers answer it, all before the second is seated.
		manager.flushRooms();
		send(host, "{\"t\":\"update\",\"s\":{\"name\":\"Host\",\"world\":301}}");
		send(member, "{\"t\":\"update\",\"s\":{\"name\":\"Member\",\"world\":301}}");
		manager.flushRooms();

		send(third, "{\"t\":\"join\",\"room\":\"r\"}");
		manager.flushRooms();

		// Both peers are asked again — including the first joiner, whose own push the second one missed.
		assertThat(countOf(hostOut, "resync")).isEqualTo(2);
		assertThat(countOf(memberOut, "resync")).isEqualTo(1);
	}

	/**
	 * Once live frames are sent only on change, an idle member emits nothing — so the heartbeat is the only
	 * thing keeping its peers from timing it out. It reaches them, and is not echoed to the sender.
	 */
	@Test
	void heartbeatReachesPeersAsAliveAndIsNotEchoed() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		long memberId = last(memberOut, "welcome").get("m").asLong();

		send(member, "{\"t\":\"heartbeat\"}");

		JsonNode alive = last(hostOut, "alive");
		assertThat(alive).isNotNull();
		assertThat(alive.get("m").asLong()).isEqualTo(memberId);
		assertThat(countOf(memberOut, "alive")).isZero();
	}

	/**
	 * The normal live update: one frame carrying whichever parts changed, delivered on the next flush under
	 * its own type so a client from before the split ignores it rather than reading a partial payload as a
	 * whole snapshot.
	 */
	@Test
	void aCoalescedUpdateIsRelayedWholeAndNotEchoed() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		long hostId = last(hostOut, "welcome").get("m").asLong();

		int echoed = countOf(hostOut, "mu");

		send(host, "{\"t\":\"update\",\"s\":{\"currentHp\":31,\"inventory\":[995]}}");
		manager.flushRooms();

		JsonNode relayed = onlyUpdate(last(memberOut, "mu"));
		assertThat(relayed.get("m").asLong()).isEqualTo(hostId);
		assertThat(relayed.get("s").get("currentHp").asInt()).isEqualTo(31);
		assertThat(relayed.get("s").get("inventory")).hasSize(1);
		// A sender is never handed back its own update.
		assertThat(countOf(hostOut, "mu")).isEqualTo(echoed);
	}

	/**
	 * Every update in the frame names its member twice: {@code m}, and {@code memberId} for plugin 1.0.50.
	 *
	 * <p>That release annotates the short name on the roster row but not on the update, so it reads Gson's
	 * default of 0 for every one of them — an id no roster holds. The peer each update describes then never
	 * receives it and stays blank, which is what leaves an applicant invisible to the host it applied to.
	 *
	 * <p>Asserted on the fanned-out frame, not the record: that frame is pasted together from pre-encoded
	 * updates rather than serialised whole, so the alias has to survive the assembly. Goes with
	 * {@code CapabilitiesController}, and the sooner the better — this frame is the hottest one there is.
	 */
	@Test
	void everyUpdateNamesItsMemberUnderBothNamesForPlugin1050() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		long hostId = last(hostOut, "welcome").get("m").asLong();

		send(host, "{\"t\":\"update\",\"s\":{\"currentHp\":31}}");
		send(host, "{\"t\":\"update\",\"s\":{\"world\":301}}");
		manager.flushRooms();

		JsonNode updates = last(memberOut, "mu").get("u");
		assertThat(updates).hasSize(2);
		for (JsonNode update : updates) {
			assertThat(update.get("m").asLong()).isEqualTo(hostId);
			assertThat(update.get("memberId").asLong()).isEqualTo(hostId);
		}
	}

	/** Several updates within one window arrive together, in the order they were sent, as one frame. */
	@Test
	void updatesWithinOneWindowArriveTogetherInOrder() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		long hostId = last(hostOut, "welcome").get("m").asLong();

		send(host, "{\"t\":\"update\",\"s\":{\"currentHp\":31}}");
		send(host, "{\"t\":\"update\",\"s\":{\"inventory\":[995]}}");
		send(host, "{\"t\":\"update\",\"s\":{\"name\":\"Host\",\"world\":301}}");
		manager.flushRooms();

		// One send, not three: order is preserved because updates are never merged.
		JsonNode updates = last(memberOut, "mu").get("u");
		assertThat(updates).hasSize(3);
		assertThat(updates.get(0).get("s").get("currentHp").asInt()).isEqualTo(31);
		assertThat(updates.get(1).get("s").get("inventory")).hasSize(1);
		assertThat(updates.get(2).get("s").get("world").asInt()).isEqualTo(301);
		assertThat(updates.get(0).get("m").asLong()).isEqualTo(hostId);
	}

	@Test
	void aLaterHelloFillsInAnAlreadySeatedMembersIdentity() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		// A joiner doesn't know its own name yet when the UI sends the join frame.
		send(member, "{\"t\":\"join\",\"room\":\"r\"}");
		long memberId = last(memberOut, "welcome").get("m").asLong();
		assertThat(nameOf(last(hostOut, "roster"), memberId)).isNull();

		send(member, "{\"t\":\"hello\",\"name\":\"Mem\",\"accountHash\":222}");

		JsonNode roster = last(hostOut, "roster");
		assertThat(nameOf(roster, memberId)).isEqualTo("Mem");
		assertThat(accountHashOf(roster, memberId)).isEqualTo(222);
	}

	@Test
	void aLaterHelloFromTheHostRenamesTheRoom() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"capacity\":3}");
		send(host, "{\"t\":\"hello\",\"name\":\"Host\",\"accountHash\":111}");

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
		PartyManager manager = new PartyManager(mapper, flaky, node, new LocalPartyBus(), new LocalNodeLoadRegistry(), sessionId -> { }, MEMBER_TIMEOUT_MS);
		PartyFrameHandler fenced = new PartyFrameHandler(manager, mapper, invites);
		fenced.onOpen(host);
		fenced.onOpen(member);
		sendTo(fenced, host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		inviteFor("Mem");
		sendTo(fenced, member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		long memberId = last(memberOut, "welcome").get("m").asLong();

		owned.set(false);
		sendTo(fenced, host, "{\"t\":\"command\",\"action\":\"KICK\",\"target\":" + memberId + "}");

		// The kick is refused, and everyone is told to reconnect elsewhere.
		assertThat(last(memberOut, "kicked")).isNull();
		assertThat(last(memberOut, "ownerChanged")).isNotNull();
		assertThat(last(hostOut, "ownerChanged")).isNotNull();
		assertThat(manager.roomCount()).isZero();
		assertThat(manager.failoverCount()).isEqualTo(1);
	}

	@Test
	void joiningUnknownRoomErrors() throws Exception {
		send(member, "{\"t\":\"join\",\"room\":\"nope\"}");
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
		PartyFrameHandler redirecting = new PartyFrameHandler(
			new PartyManager(mapper, foreign, node, new LocalPartyBus(), new LocalNodeLoadRegistry(), sessionId -> { }, MEMBER_TIMEOUT_MS), mapper, invites);
		List<String> out = new ArrayList<>();
		FakeSession joiner = session("joiner", out);
		redirecting.onOpen(joiner);

		sendTo(redirecting, joiner, "{\"t\":\"join\",\"room\":\"elsewhere\"}");

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

			public java.util.Optional<String> preferredNode(int selfMembers) {
				return java.util.Optional.ofNullable(lighter.get());
			}
		};
		PartyManager loaded = new PartyManager(
			mapper, new LocalPartyOwnershipService(node), node, new LocalPartyBus(), load,
			sessionId -> { }, MEMBER_TIMEOUT_MS);
		PartyFrameHandler placing = new PartyFrameHandler(loaded, mapper, invites);
		List<String> out = new ArrayList<>();
		FakeSession newHost = session("newHost", out);
		placing.onOpen(newHost);

		sendTo(placing, newHost, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");

		// Redirected rather than claimed: nothing is hosted here, and the client will re-send host there.
		assertThat(last(out, "redirect").get("nodeId").asText()).isEqualTo("node-b");
		assertThat(last(out, "welcome")).isNull();
		assertThat(loaded.roomCount()).isZero();
		assertThat(loaded.rebalanceCount()).isEqualTo(1);

		// Once the room exists here, load no longer decides: a host re-sending its frame after a reconnect
		// must land back on its own room rather than being bounced to whichever node is lightest today.
		lighter.set(null);
		sendTo(placing, newHost, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		assertThat(last(out, "welcome").get("status").asText()).isEqualTo("HOST");
		assertThat(loaded.roomCount()).isEqualTo(1);

		lighter.set("node-b");
		out.clear();
		sendTo(placing, newHost, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		assertThat(last(out, "redirect")).isNull();
		assertThat(loaded.rebalanceCount()).isEqualTo(1);

		// A host that arrived on the node-hint path was sent here on purpose. Re-placing it would let two
		// nodes with slightly different load snapshots bounce the same client between them.
		List<String> hintedOut = new ArrayList<>();
		FakeSession hinted = session("hinted", hintedOut);
		hinted.nodeHinted = true;
		placing.onOpen(hinted);
		sendTo(placing, hinted, "{\"t\":\"host\",\"room\":\"r2\",\"hostName\":\"Other\",\"capacity\":3}");

		assertThat(last(hintedOut, "redirect")).isNull();
		assertThat(last(hintedOut, "welcome").get("status").asText()).isEqualTo("HOST");
		assertThat(loaded.rebalanceCount()).isEqualTo(1);
	}

	@Test
	void aConnectionThatGoesAwayKeepsItsSeat() throws Exception {
		long memberId = hostWithAdmittedMember();
		assertThat(manager.connectedMembers()).isEqualTo(2);

		// However the connection went — a close callback, or a socket that died without one — the member
		// itself has not said it is leaving, and is usually back well inside the timeout.
		handler.onClose(member.id(), "NORMAL");
		manager.pruneRoom("r");

		// The seat is still theirs: nobody is told they left, and the roster still names them.
		assertThat(manager.prunedCount()).isZero();
		assertThat(last(hostOut, "memberLeft")).isNull();
		assertThat(statusOf(last(hostOut, "roster"), memberId)).isEqualTo("MEMBER");
		assertThat(manager.roomCount()).isEqualTo(1);
		// What did change is what the room costs this node: a held seat is serving nobody.
		assertThat(manager.connectedMembers()).isEqualTo(1);
	}

	/**
	 * A held seat is only useful if the party can see it is being held. Peers judge presence by silence
	 * otherwise, and silence takes their whole timeout to mean anything — twenty seconds of a member who is
	 * plainly not there still looking present.
	 */
	@Test
	void aHeldSeatIsReportedOfflineAtOnce() throws Exception {
		long memberId = hostWithAdmittedMember();
		send(member, "{\"t\":\"hello\",\"accountHash\":222,\"name\":\"Mem\"}");
		assertThat(offlineIn(last(hostOut, "roster"), memberId)).isFalse();

		handler.onClose(member.id(), "NORMAL");

		assertThat(offlineIn(last(hostOut, "roster"), memberId)).isTrue();

		// And taking the seat back says so on the same frame, without waiting for their first update.
		List<String> againOut = new ArrayList<>();
		FakeSession again = session("member-again", againOut);
		handler.onOpen(again);
		send(again, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");

		assertThat(offlineIn(last(hostOut, "roster"), memberId)).isFalse();
	}

	/** The point of holding the seat: the same player comes back to it rather than applying all over again. */
	@Test
	void aMemberComingBackTakesItsOwnSeat() throws Exception {
		long memberId = hostWithAdmittedMember();
		// As a real client does: a joiner only knows its own account once it is in-game, which is usually
		// after it was seated, so the seat learns who is in it from a later hello.
		send(member, "{\"t\":\"hello\",\"accountHash\":222,\"name\":\"Mem\"}");
		handler.onClose(member.id(), "NORMAL");

		// A fresh connection, as a restarted client always is — the account hash is what says who it is.
		List<String> againOut = new ArrayList<>();
		FakeSession again = session("member-again", againOut);
		handler.onOpen(again);
		send(again, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");

		JsonNode welcome = last(againOut, "welcome");
		// Their own member id and their own admission: not a second seat, and not a new application.
		assertThat(welcome.get("m").asLong()).isEqualTo(memberId);
		assertThat(welcome.get("status").asText()).isEqualTo("MEMBER");
		assertThat(last(hostOut, "roster").get("members")).hasSize(2);
		assertThat(manager.connectedMembers()).isEqualTo(2);

		// And the connection that came back is the one the room now speaks to.
		send(host, "{\"t\":\"update\",\"s\":{\"currentHp\":50}}");
		manager.flushRooms();
		assertThat(onlyUpdate(last(againOut, "mu")).get("s").get("currentHp").asInt()).isEqualTo(50);
	}

	/** A host is not a special case of coming back — except that the party is waiting for it. */
	@Test
	void aHostComingBackTakesTheRoomItLeft() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"accountHash\":111,\"capacity\":3}");
		long hostId = last(hostOut, "welcome").get("m").asLong();
		inviteFor("Mem");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222,\"invited\":true}");

		handler.onClose(host.id(), "NORMAL");
		// Nobody is told the party is over, because it isn't: its host is on its way back.
		assertThat(last(memberOut, "roster").get("closed").asBoolean()).isFalse();
		assertThat(manager.roomCount()).isEqualTo(1);

		List<String> againOut = new ArrayList<>();
		FakeSession again = session("host-again", againOut);
		handler.onOpen(again);
		send(again, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"accountHash\":111,\"capacity\":3}");

		JsonNode welcome = last(againOut, "welcome");
		assertThat(welcome.get("m").asLong()).isEqualTo(hostId);
		assertThat(welcome.get("status").asText()).isEqualTo("HOST");
		// The member never went anywhere: same room, same roster, nobody re-admitted.
		assertThat(last(memberOut, "roster").get("members")).hasSize(2);
		assertThat(manager.roomCount()).isEqualTo(1);
	}

	/**
	 * The shape a hard kill actually leaves: no close callback at all, just a socket nothing has torn down.
	 * The relaunched client has to be recognised anyway, and the old connection's close — whenever the
	 * network gets around to it — must not then cut off the one that replaced it.
	 */
	@Test
	void aMemberComingBackPastItsOwnHalfOpenSocketKeepsTheSeat() throws Exception {
		long memberId = hostWithAdmittedMember();
		send(member, "{\"t\":\"hello\",\"accountHash\":222,\"name\":\"Mem\"}");

		List<String> againOut = new ArrayList<>();
		FakeSession again = session("member-again", againOut);
		handler.onOpen(again);
		send(again, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");

		// One seat, taken back — not a second one beside a member nobody can reach.
		assertThat(last(againOut, "welcome").get("m").asLong()).isEqualTo(memberId);
		assertThat(last(hostOut, "roster").get("members")).hasSize(2);

		// The abandoned socket finally closes. It speaks for nobody now, and saying so must not take the
		// live connection's seat with it.
		handler.onClose(member.id(), "timeout");

		send(host, "{\"t\":\"update\",\"s\":{\"currentHp\":31}}");
		manager.flushRooms();
		assertThat(onlyUpdate(last(againOut, "mu")).get("s").get("currentHp").asInt()).isEqualTo(31);
		assertThat(manager.connectedMembers()).isEqualTo(2);
	}

	/** Holding seats means a host can kick someone who is away — and it has to stick when they come back. */
	@Test
	void aKickedMemberDoesNotWalkBackInOnItsReconnect() throws Exception {
		long memberId = hostWithAdmittedMember();
		send(member, "{\"t\":\"hello\",\"accountHash\":222,\"name\":\"Mem\"}");
		handler.onClose(member.id(), "NORMAL");
		send(host, "{\"t\":\"command\",\"action\":\"KICK\",\"target\":" + memberId + "}");

		List<String> againOut = new ArrayList<>();
		FakeSession again = session("member-again", againOut);
		handler.onOpen(again);
		// Coming back on a real invite -- hostWithAdmittedMember recorded one, and it has not expired. A kick
		// outranks it: the host's decision about a person is the later one, and it is about them, not about
		// how they were let in the first time.
		send(again, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222,\"invited\":true}");

		// They are back in the applicant queue, where the host decides again — not back in the party.
		assertThat(last(againOut, "welcome").get("status").asText()).isEqualTo("PENDING");

		// And admitting them is the host changing its mind, which it may: the removal stops counting.
		long backId = last(againOut, "welcome").get("m").asLong();
		send(host, "{\"t\":\"command\",\"action\":\"ADMIT\",\"target\":" + backId + "}");
		assertThat(statusOf(last(hostOut, "roster"), backId)).isEqualTo("MEMBER");
	}

	@Test
	void aSeatNobodyComesBackForIsSwept() throws Exception {
		// A timeout nothing can be fresh enough for, so the sweep sees every seat as one nobody came back to.
		NodeIdentity node = new NodeIdentity("node-a", true);
		PartyManager impatient = new PartyManager(mapper, new LocalPartyOwnershipService(node), node,
			new LocalPartyBus(), new LocalNodeLoadRegistry(), sessionId -> { }, -1L);
		PartyFrameHandler sweeping = new PartyFrameHandler(impatient, mapper, invites);
		sweeping.onOpen(host);
		sweeping.onOpen(member);
		sendTo(sweeping, host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		inviteFor("Mem");
		sendTo(sweeping, member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		sweeping.onClose(host.id(), "NORMAL");

		impatient.pruneRoom("r");

		// The party ends the way it always has when its host is gone — only now after a window in which it
		// could have come back, rather than the instant its socket dropped.
		assertThat(last(memberOut, "roster").get("closed").asBoolean()).isTrue();
		assertThat(impatient.roomCount()).isZero();
		assertThat(impatient.prunedCount()).isEqualTo(2);
	}

	@Test
	void sweepingDropsAMemberThatWentSilentWhileItsSocketStillLooksOpen() throws Exception {
		// The case isOpen() cannot see: the client is gone but nothing closed the connection, so the session
		// reports open indefinitely and the room never empties. Traffic is the only honest signal.
		PartyManager impatient = new PartyManager(
			mapper, new LocalPartyOwnershipService(new NodeIdentity("node-a", true)),
			// -1 rather than 0: with a zero timeout a member stamped in the same millisecond as the sweep is
			// not yet stale, which makes the assertion depend on the clock. Negative means "everything is".
			new NodeIdentity("node-a", true), new LocalPartyBus(), new LocalNodeLoadRegistry(),
			sessionId -> { }, -1L);
		PartyFrameHandler sweeping = new PartyFrameHandler(impatient, mapper, invites);
		sweeping.onOpen(host);
		sweeping.onOpen(member);
		sendTo(sweeping, host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		inviteFor("Mem");
		sendTo(sweeping, member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		assertThat(impatient.connectedMembers()).isEqualTo(2);

		// Both sessions still report open — only silence gives them away.
		assertThat(host.isOpen()).isTrue();
		assertThat(member.isOpen()).isTrue();
		impatient.pruneRoom("r");

		assertThat(impatient.prunedCount()).isEqualTo(2);
		assertThat(impatient.roomCount()).isZero();
	}

	@Test
	void sweepingAHostTakesItsAdvertisementWithIt() throws Exception {
		// The sweep is what drops the ad, so this needs a timeout no seat can be fresh enough for.
		NodeIdentity node = new NodeIdentity("node-a", true);
		PartyManager impatient = new PartyManager(mapper, new LocalPartyOwnershipService(node), node,
			new LocalPartyBus(), new LocalNodeLoadRegistry(), adsDropped::add, -1L);
		PartyFrameHandler sweeping = new PartyFrameHandler(impatient, mapper, invites);
		sweeping.onOpen(host);
		sendTo(sweeping, host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");

		// The board renews an ad for as long as its host's connection is up, and the client this sweep exists
		// to catch — logged out of the game, still connected — keeps that connection up indefinitely, leaving
		// the board advertising a room that no longer exists.
		impatient.pruneRoom("r");

		assertThat(adsDropped).containsExactly("host");
	}

	@Test
	void aConnectionGoingAwayLeavesTheAdvertisementAlone() throws Exception {
		hostWithAdmittedMember();

		// Neither seat has been given up yet, so there is still a party being advertised. The ad has its own
		// TTL for the case where the host really is gone; nothing here should shorten it.
		handler.onClose(member.id(), "NORMAL");
		handler.onClose(host.id(), "NORMAL");
		manager.pruneRoom("r");

		assertThat(adsDropped).isEmpty();
		assertThat(manager.roomCount()).isEqualTo(1);
	}

	@Test
	void sweepingLeavesALiveRoomAlone() throws Exception {
		hostWithAdmittedMember();
		manager.pruneRoom("r");
		assertThat(adsDropped).isEmpty();
		// Nothing closed, so nothing removed — and no discard, which would otherwise be free to release the
		// lock of a room created microseconds ago whose host is still being seated.
		assertThat(manager.prunedCount()).isZero();
		assertThat(manager.roomCount()).isEqualTo(1);
		assertThat(manager.connectedMembers()).isEqualTo(2);
	}

	@Test
	void readyChecksAndSpecDrainsFanOutToPeers() throws Exception {
		long memberId = hostWithAdmittedMember();

		send(host, "{\"t\":\"readyStart\",\"checkId\":7,\"starter\":\"Host\"}");
		JsonNode start = last(memberOut, "readyStart");
		assertThat(start.get("checkId").asLong()).isEqualTo(7);
		assertThat(start.get("starter").asText()).isEqualTo("Host");
		// The sender doesn't receive its own broadcast (it applies the check locally).
		assertThat(last(hostOut, "readyStart")).isNull();

		send(member, "{\"t\":\"ready\",\"checkId\":7}");
		assertThat(last(hostOut, "ready").get("m").asLong()).isEqualTo(memberId);

		send(member, "{\"t\":\"specDrain\",\"npcIndex\":42,\"weapon\":\"DRAGON_WARHAMMER\",\"hit\":25,"
			+ "\"world\":301}");
		JsonNode drain = last(hostOut, "specDrain");
		assertThat(drain.get("npcIndex").asInt()).isEqualTo(42);
		assertThat(drain.get("weapon").asText()).isEqualTo("DRAGON_WARHAMMER");
		assertThat(drain.get("hit").asInt()).isEqualTo(25);
	}

	@Test
	void pendingApplicantCannotFanOutReadyOrSpec() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":2}");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\"}"); // stays PENDING

		send(member, "{\"t\":\"readyStart\",\"checkId\":1,\"starter\":\"Mem\"}");
		send(member, "{\"t\":\"specDrain\",\"npcIndex\":1,\"weapon\":\"BANDOS_GODSWORD\",\"hit\":10}");

		assertThat(last(hostOut, "readyStart")).isNull();
		assertThat(last(hostOut, "specDrain")).isNull();
	}

	@Test
	void fcRequestReachesOnlyTheTargetAndOnlyFromTheHost() throws Exception {
		long memberId = hostWithAdmittedMember();

		send(host, "{\"t\":\"fcRequest\",\"target\":" + memberId + ",\"kind\":\"FC\",\"friendsChat\":\"Zuk\"}");
		JsonNode request = last(memberOut, "fcRequest");
		assertThat(request.get("kind").asText()).isEqualTo("FC");
		assertThat(request.get("friendsChat").asText()).isEqualTo("Zuk");
		assertThat(request.get("host").asText()).isEqualTo("Host");

		// A member cannot send prompts back at the host.
		send(member, "{\"t\":\"fcRequest\",\"target\":1,\"kind\":\"FC\",\"friendsChat\":\"nope\"}");
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

		send(host, "{\"t\":\"setMeta\",\"meta\":{\"host\":\"Host\",\"world\":\"301\",\"lootRule\":\"FFA\"}}");
		JsonNode meta = last(memberOut, "meta");
		assertThat(meta.get("meta").get("world").asText()).isEqualTo("301");
		assertThat(meta.get("meta").get("lootRule").asText()).isEqualTo("FFA");
		// Relayed verbatim: the server stores the payload without interpreting it.
		assertThat(last(hostOut, "meta")).isNull();

		// A member cannot rewrite the ad settings under the host.
		send(member, "{\"t\":\"setMeta\",\"meta\":{\"host\":\"Mem\",\"world\":\"999\"}}");
		assertThat(last(hostOut, "meta")).isNull();
		assertThat(last(memberOut, "meta").get("meta").get("world").asText()).isEqualTo("301");

		// A later joiner gets the current settings in its seating snapshot.
		List<String> lateOut = new ArrayList<>();
		FakeSession late = session("late", lateOut);
		handler.onOpen(late);
		inviteFor("Late");
		send(late, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Late\",\"invited\":true}");
		assertThat(last(lateOut, "meta").get("meta").get("lootRule").asText()).isEqualTo("FFA");
	}

	@Test
	void hostTransferCommitMovesAuthoritativeHostStatus() throws Exception {
		long memberId = hostWithAdmittedMember();
		long hostId = last(hostOut, "welcome").get("m").asLong();

		send(host, "{\"t\":\"transferHost\",\"kind\":\"OFFER\",\"target\":" + memberId
			+ ",\"newHostKey\":\"k\",\"newHostName\":\"Mem\",\"hostStays\":true}");
		assertThat(last(memberOut, "transferHost").get("kind").asText()).isEqualTo("OFFER");

		// ACCEPT is the one member-initiated step, addressed back at the host.
		send(member, "{\"t\":\"transferHost\",\"kind\":\"ACCEPT\",\"target\":" + hostId + "}");
		assertThat(last(hostOut, "transferHost").get("kind").asText()).isEqualTo("ACCEPT");

		send(host, "{\"t\":\"transferHost\",\"kind\":\"COMMIT\",\"target\":" + memberId
			+ ",\"newHostKey\":\"k\",\"hostStays\":true}");
		JsonNode roster = last(memberOut, "roster");
		assertThat(statusOf(roster, memberId)).isEqualTo("HOST");
		assertThat(statusOf(roster, hostId)).isEqualTo("MEMBER");
		assertThat(roster.get("host").asText()).isEqualTo("Mem");
	}

	@Test
	void hostTransferCommitDropsTheOldHostWhenItDoesNotStay() throws Exception {
		long memberId = hostWithAdmittedMember();
		long hostId = last(hostOut, "welcome").get("m").asLong();

		send(host, "{\"t\":\"transferHost\",\"kind\":\"COMMIT\",\"target\":" + memberId
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
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":4}");

		// Seated between the host and a healthy member, so the fan-out still has someone to visit after the
		// removal — which is exactly when the iterator noticed and threw.
		List<String> deadOut = new ArrayList<>();
		FakeSession dead = session("dead", deadOut);
		handler.onOpen(dead);
		inviteFor("Dead");
		send(dead, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Dead\",\"invited\":true}");
		inviteFor("Mem");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		long memberId = last(memberOut, "welcome").get("m").asLong();

		// The peer's connection is gone: writing to it fails, and the container closes it on our thread.
		dead.onSend = () -> handler.onClose(dead.id(), "broken pipe");

		send(host, "{\"t\":\"update\",\"s\":{\"name\":\"Host\",\"world\":301}}");

		// The healthy member still got the frame; the dead peer only lost its connection, not its seat.
		manager.flushRooms();
		assertThat(onlyUpdate(last(memberOut, "mu")).get("s").get("world").asInt()).isEqualTo(301);
		assertThat(last(memberOut, "roster").get("members")).hasSize(3);
		assertThat(statusOf(last(memberOut, "roster"), memberId)).isEqualTo("MEMBER");
	}

	/** The same hazard on the leave path: onLeave broadcasts, and a dead peer removes itself mid-fan-out. */
	@Test
	void aDeadPeerDoesNotBreakTheFanOutOfSomeoneElseLeaving() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":6}");

		List<String> deadOut = new ArrayList<>();
		FakeSession dead = session("dead", deadOut);
		List<String> leaverOut = new ArrayList<>();
		FakeSession leaver = session("leaver", leaverOut);
		handler.onOpen(dead);
		handler.onOpen(leaver);
		// Seated so the healthy member comes after the dead one in the fan-out, which is when it mattered.
		inviteFor("Dead");
		send(dead, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Dead\",\"invited\":true}");
		inviteFor("Mem");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		inviteFor("Leaver");
		send(leaver, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Leaver\",\"invited\":true}");
		long leaverId = last(leaverOut, "welcome").get("m").asLong();

		dead.onSend = () -> handler.onClose(dead.id(), "broken pipe");

		send(leaver, "{\"t\":\"leave\"}");

		assertThat(last(memberOut, "memberLeft").get("m").asLong()).isEqualTo(leaverId);
		// Only the leaver said it was leaving; the dead peer keeps the seat its connection let go of.
		assertThat(last(memberOut, "roster").get("members")).hasSize(3);
	}

	@Test
	void hostLeavingClosesTheRoomForMembers() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		inviteFor("Mem");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		// Said, not merely suffered: a host that leaves has disbanded the party (see
		// aHostComingBackTakesTheRoomItLeft for the connection simply going away).
		send(host, "{\"t\":\"leave\"}");

		JsonNode roster = last(memberOut, "roster");
		assertThat(roster.get("closed").asBoolean()).isTrue();
	}

	/**
	 * The two-speed flush: an ordinary update waits out the idle window, one the sender marked urgent does
	 * not — and the waiting one goes out with it rather than being dropped.
	 */
	@Test
	void anUrgentUpdateFlushesTheOnesWaitingOutTheIdleWindow() throws Exception {
		hostWithAdmittedMember();
		// A window long enough that nothing elapses it during the test; the gate is driven by the flag alone.
		long idleMs = 60_000;

		// The first flush after a quiet spell is always immediate — the gap is measured from the last flush.
		send(host, "{\"t\":\"update\",\"s\":{\"currentHp\":50}}");
		manager.flushRooms(idleMs);
		assertThat(countOf(memberOut, "mu")).isEqualTo(1);

		// Now the room has just sent, so an ordinary update waits.
		send(host, "{\"t\":\"update\",\"s\":{\"currentHp\":51}}");
		manager.flushRooms(idleMs);
		assertThat(countOf(memberOut, "mu")).isEqualTo(1);

		// Damage taken. It goes out at once, and takes the held update with it in arrival order.
		send(host, "{\"t\":\"update\",\"g\":true,\"s\":{\"currentHp\":22}}");
		manager.flushRooms(idleMs);
		assertThat(countOf(memberOut, "mu")).isEqualTo(2);

		JsonNode updates = last(memberOut, "mu").get("u");
		assertThat(updates).hasSize(2);
		assertThat(updates.get(0).get("s").get("currentHp").asInt()).isEqualTo(51);
		assertThat(updates.get(1).get("s").get("currentHp").asInt()).isEqualTo(22);
	}

	// ---- helpers ------------------------------------------------------------

	/**
	 * Grant these players a seat in {@link #ROOM}, which is what lets a single join frame seat one as a
	 * MEMBER. Without it the joiner is an ordinary applicant no matter what its frame claims.
	 */
	private void inviteFor(String... names) {
		for (String name : names) {
			invites.grant(ROOM, name);
		}
	}

	/** Host a room and admit one member (an invited joiner is seated straight away); returns its id. */
	private long hostWithAdmittedMember() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		inviteFor("Mem");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		return last(memberOut, "welcome").get("m").asLong();
	}

	/**
	 * The drain race: a member reconnects after its owner node drained, but before the host has re-hosted
	 * the room on its new node. It must be told to retry — answering "no room" strands it in a party whose
	 * host it can never see again, which is the failure this whole handover path exists to prevent.
	 */
	@Test
	void joinDuringHandoverIsRetriableNotTerminal() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":4}");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");

		// The owning node drains on shutdown: everyone is told, the lock is handed over.
		manager.drain("r", true);
		assertThat(last(memberOut, "ownerChanged")).isNotNull();

		// The member reconnects and re-sends its join before the host has re-claimed the room.
		memberOut.clear();
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222}");

		JsonNode pending = last(memberOut, "ownerPending");
		assertThat(pending).isNotNull();
		assertThat(pending.get("retryAfterMs").asLong()).isPositive();
		assertThat(last(memberOut, "error")).isNull();

		// Once the host re-hosts, the retry seats the member for real.
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":4}");
		memberOut.clear();
		inviteFor("Mem");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"accountHash\":222,\"invited\":true}");
		assertThat(last(memberOut, "welcome")).isNotNull();
		assertThat(last(memberOut, "welcome").get("status").asText()).isEqualTo("MEMBER");
	}

	/**
	 * A room whose owner died is taken over by the scan, and the takeover settles where everyone goes: the
	 * reclaiming node holds the lock, defers joiners until the host rebuilds the room, then seats them.
	 */
	@Test
	void reclaimTakesOverAnExpiredRoomAndWaitsForItsHost() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":4}");
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
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\"}");
		assertThat(last(memberOut, "ownerPending")).isNotNull();
		assertThat(last(memberOut, "redirect")).isNull();
		assertThat(last(memberOut, "error")).isNull();

		// The host returns and rebuilds the room on the node that reclaimed it.
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\",\"capacity\":4}");
		memberOut.clear();
		inviteFor("Mem");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
		assertThat(last(memberOut, "welcome").get("status").asText()).isEqualTo("MEMBER");
	}

	/** A claim announced by another node drops this node's copy of the room immediately. */
	@Test
	void busOwnerChangedDrainsARoomWeNoLongerOwn() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		inviteFor("Mem");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");
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
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");

		bus.listener().onOwnerChanged("someone-elses-room", "node-b");
		bus.listener().onForceReconnect("someone-elses-room");

		assertThat(manager.roomCount()).isEqualTo(1);
		assertThat(manager.failoverCount()).isZero();
	}

	/** Force-reconnect hands the room over: members are sent off and the lock is released for the taking. */
	@Test
	void busForceReconnectDrainsAndReleasesTheRoom() throws Exception {
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"capacity\":3}");
		inviteFor("Mem");
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\",\"invited\":true}");

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
		send(host, "{\"t\":\"host\",\"room\":\"r\",\"hostName\":\"Host\",\"activityId\":\"cox\"}");
		manager.discard("r");

		memberOut.clear();
		send(member, "{\"t\":\"join\",\"room\":\"r\",\"name\":\"Mem\"}");
		assertThat(last(memberOut, "ownerPending")).isNull();
		assertThat(last(memberOut, "error").get("detail").asText()).isEqualTo("no room");
	}

	private void send(FakeSession session, String json) {
		sendTo(handler, session, json);
	}

	/** For the tests that stand up a frame handler of their own over a differently-wired manager. */
	private static void sendTo(PartyFrameHandler target, FakeSession session, String json) {
		target.onMessage(session.id(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private JsonNode last(List<String> out, String type) throws Exception {
		JsonNode found = null;
		for (String json : out) {
			JsonNode node = mapper.readTree(json);
			if (type.equals(node.path("t").asText())) {
				found = node;
			}
		}
		return found;
	}

	/** The single update inside a memberUpdates frame, for the cases that only ever produce one. */
	private static JsonNode onlyUpdate(JsonNode frame) {
		if (frame == null) {
			return null;
		}
		JsonNode updates = frame.get("u");
		return updates == null || updates.isEmpty() ? null : updates.get(updates.size() - 1);
	}

	private int countOf(List<String> out, String type) throws Exception {
		int n = 0;
		for (String json : out) {
			if (type.equals(mapper.readTree(json).path("t").asText())) {
				n++;
			}
		}
		return n;
	}

	private static String nameOf(JsonNode roster, long memberId) {
		for (JsonNode m : roster.get("members")) {
			if (m.get("m").asLong() == memberId) {
				return m.hasNonNull("name") ? m.get("name").asText() : null;
			}
		}
		return null;
	}

	private static long accountHashOf(JsonNode roster, long memberId) {
		for (JsonNode m : roster.get("members")) {
			if (m.get("m").asLong() == memberId) {
				return m.get("accountHash").asLong();
			}
		}
		return 0;
	}

	private static String statusOf(JsonNode roster, long memberId) {
		for (JsonNode m : roster.get("members")) {
			if (m.get("m").asLong() == memberId) {
				return m.get("status").asText();
			}
		}
		return null;
	}

	private static boolean offlineIn(JsonNode roster, long memberId) {
		for (JsonNode m : roster.get("members")) {
			if (m.get("m").asLong() == memberId) {
				return m.get("offline").asBoolean();
			}
		}
		throw new AssertionError("member " + memberId + " is not on the roster");
	}

	private static FakeSession session(String id, List<String> out) {
		return new FakeSession(id, out);
	}

	/**
	 * A connection with no transport under it: frames are collected as strings, and the two things a test
	 * needs to drive — a socket that has quietly died, and one that closes itself while being written to —
	 * are fields rather than stubs.
	 */
	private static final class FakeSession implements SocketSession {
		private final String id;
		private final List<String> out;
		private volatile boolean open = true;
		private volatile boolean nodeHinted;
		/**
		 * Runs instead of recording the frame, for the tests that model a peer whose connection breaks
		 * during someone else's fan-out and is removed from the room by the close callback re-entrantly.
		 */
		private volatile Runnable onSend;

		FakeSession(String id, List<String> out) {
			this.id = id;
			this.out = out;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public boolean isOpen() {
			return open;
		}

		@Override
		public void send(byte[] frame) {
			Runnable hook = onSend;
			if (hook != null) {
				// Deliberately no throw: the SocketSession contract says an ordinary send failure must not
				// unwind through another member's fan-out, so a transport swallows it. What is under test
				// is the re-entrant removal, not the exception.
				hook.run();
				return;
			}
			// Frames go out as binary (UTF-8 JSON), so they are read back the same way.
			out.add(new String(frame, java.nio.charset.StandardCharsets.UTF_8));
		}

		@Override
		public void sendText(String json) {
			out.add(json);
		}

		@Override
		public void close() {
			open = false;
		}

		@Override
		public boolean nodeHinted() {
			return nodeHinted;
		}
	}
}

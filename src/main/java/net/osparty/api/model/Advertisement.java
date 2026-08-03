package net.osparty.api.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.List;
import lombok.Data;

/**
 * One advertisement on the board: what a host is running, who is in it, and how to reach it.
 *
 * <p>Not a party. The party is the live room on its owning node ({@code net.osparty.api.party.PartyRoom});
 * this is the listing that lets somebody find it. The two were both called {@code Party} until the live
 * layer took the name it had the better claim to.
 *
 * <p>Field names here are the wire — no {@code @JsonProperty} anywhere, so Jackson serialises by field name
 * and the plugin reads them straight back. {@code privateAd} was {@code privateParty}; the plugin's own
 * model has to match it or the flag silently reads false on every ad — hence the alias below, for as long
 * as a plugin that predates the rename is still in the hub.
 */
@Data
public class Advertisement {
	private String id;
	private String activity;
	private String host;
	/**
	 * The current host's account hash, or 0 when the client never reported one. Kept as a field of
	 * its own rather than read back from {@code members.get(0)}: a host transfer rewrites
	 * {@code host} without touching the member list, so member zero goes stale the moment a party
	 * changes hands.
	 */
	private long hostAccountHash;
	private String description;
	private int size;
	private int capacity;
	private String world;
	private String layout;
	private boolean hardMode;
	private int invocation;
	/** Chambers of Xeric team-size scaling as advertised (e.g. "3+4"); null/empty when unset. */
	private String coxScale;
	private long createdAt;
	/**
	 * Cluster-wide revision, allocated from Redis on every meaningful write and never on a TTL touch.
	 *
	 * <p>It is what lets a client that already holds the board resume instead of being sent all of it
	 * again: it reconnects saying how far it got, and the node it lands on answers with the ads whose
	 * revision is higher. A timestamp would nearly do, but it would make correctness depend on the nodes'
	 * clocks agreeing; one INCR on a path that is already writing does not.
	 */
	private long seq;
	/**
	 * The node whose memory holds this party's live room, or null when nobody has said.
	 *
	 * <p>A live party is node-affine — its state exists on one pod — but discovery is not, so a joiner
	 * landed wherever the gateway put it and was redirected from there, at the cost of a reconnect. With two
	 * pods that was half of all joins. A joiner that already holds the advertisement can instead open its
	 * connection on the right pod to begin with.
	 *
	 * <p>Reported by the host rather than inferred here: a host on an older plugin keeps two sockets, whose
	 * pods need not agree, so this node is the one thing the server cannot work out for itself. It is a hint
	 * either way — a room moves when its node drains, and the ad only catches up on the host's next update —
	 * so the redirect stays as the answer for a stamp that has gone stale.
	 */
	private String node;
	private String passphrase;
	private int minKillCount;
	private int minHardModeKillCount;
	private List<Member> members;
	private boolean privateAd;
	private String inviteCode;
	private String lootRule;
	private boolean ironmanOnly;
	private String hostAccountType;
	private List<String> requiredRoles;
	private String hostRole;
	private List<String> neededRoles;
	private boolean learner;
	private boolean teacher;
	private String discordChannelId;
	private String discordInviteUrl;

	/**
	 * {@code privateAd} under the name plugin 1.0.50 knows it by, in both directions.
	 *
	 * <p>A getter and a setter rather than a {@code @JsonAlias}, because this class is also how an ad is
	 * stored: it goes to Redis through the same mapper, so whatever the wire emits has to be readable back.
	 *
	 * <p>Goes when {@link net.osparty.api.web.CapabilitiesController} does.
	 */
	@JsonGetter("privateParty")
	public boolean isPrivateParty() {
		return privateAd;
	}

	@JsonSetter("privateParty")
	public void setPrivateParty(boolean privateParty) {
		this.privateAd = privateParty;
	}

	public static Advertisement copyOf(Advertisement src) {
		Advertisement c = new Advertisement();
		c.id = src.id;
		c.activity = src.activity;
		c.host = src.host;
		c.hostAccountHash = src.hostAccountHash;
		c.description = src.description;
		c.size = src.size;
		c.capacity = src.capacity;
		c.world = src.world;
		c.layout = src.layout;
		c.hardMode = src.hardMode;
		c.invocation = src.invocation;
		c.coxScale = src.coxScale;
		c.createdAt = src.createdAt;
		c.seq = src.seq;
		c.node = src.node;
		c.passphrase = src.passphrase;
		c.minKillCount = src.minKillCount;
		c.minHardModeKillCount = src.minHardModeKillCount;
		c.members = src.members;
		c.privateAd = src.privateAd;
		c.inviteCode = src.inviteCode;
		c.lootRule = src.lootRule;
		c.ironmanOnly = src.ironmanOnly;
		c.hostAccountType = src.hostAccountType;
		c.requiredRoles = src.requiredRoles;
		c.hostRole = src.hostRole;
		c.neededRoles = src.neededRoles;
		c.learner = src.learner;
		c.teacher = src.teacher;
		c.discordChannelId = src.discordChannelId;
		c.discordInviteUrl = src.discordInviteUrl;
		return c;
	}
}

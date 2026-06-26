package net.osparty.api.model;

import java.util.List;
import lombok.Data;

/**
 * A party advertisement, serialised exactly as the RuneLite plugin's
 * {@code net.osparty.model.Party} expects it. {@code passphrase} is the key to
 * the live peer-to-peer room; {@code members}/{@code size} are advisory only
 * (the authoritative roster lives in that room, not here).
 */
@Data
public class Party
{
	private String id;
	private String activity;
	private String host;
	private String description;
	private int size;
	private int capacity;
	private String world;
	private long createdAt;
	private String passphrase;
	private int minKillCount;
	private int minHardModeKillCount;
	private List<String> members;

	/** Private parties are not returned by search — they're joined via {@link #inviteCode}. */
	private boolean privateParty;

	/** Short server-generated code used to look up this party (esp. when private). */
	private String inviteCode;

	/** Loot rule: FFA / SPLIT / UNSPECIFIED. */
	private String lootRule;

	/** When true, only ironman accounts should join. */
	private boolean ironmanOnly;

	/** The host's account type (NORMAL / IRONMAN / …), for display. */
	private String hostAccountType;
}

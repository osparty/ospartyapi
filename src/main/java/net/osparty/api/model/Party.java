package net.osparty.api.model;

import java.util.List;
import lombok.Data;

/**
 * A party advertisement, serialised exactly as the RuneLite plugin's
 * {@code com.aioparty.model.Party} expects it. {@code passphrase} is the key to
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
}

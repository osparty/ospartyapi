package net.osparty.api.model;

import java.util.List;
import lombok.Data;

@Data
public class PartyUpdate {
	private Integer size;
	private String world;
	private String layout;
	private List<String> neededRoles;
	private String description;
	private Integer capacity;
	private String lootRule;
	private Boolean ironmanOnly;
	private Boolean privateParty;
	private Integer minKillCount;
	private Integer minHardModeKillCount;
	private Integer invocation;
	private Boolean hardMode;
}

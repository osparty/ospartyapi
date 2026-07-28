package net.osparty.api.model;

import java.util.List;
import lombok.Data;

@Data
public class PartyUpdate {
	private Integer size;
	private List<Member> members;
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
	private String coxScale;
	private List<String> requiredRoles;
	private String hostRole;
	private Boolean learner;
	private Boolean teacher;
	/** The pod the host's live room is on, so joiners can reach it without a redirect. */
	private String node;
}

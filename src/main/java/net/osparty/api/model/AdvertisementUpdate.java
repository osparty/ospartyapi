package net.osparty.api.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import lombok.Data;

/** A host's patch to its own ad. Inbound only, so the 1.0.50 field name is an alias rather than a property. */
@Data
public class AdvertisementUpdate {
	private Integer size;
	private List<Member> members;
	private String world;
	private String layout;
	private List<String> neededRoles;
	private String description;
	private Integer capacity;
	private String lootRule;
	private Boolean ironmanOnly;
	@JsonAlias("privateParty")
	private Boolean privateAd;
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

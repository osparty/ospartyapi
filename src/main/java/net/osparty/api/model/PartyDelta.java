package net.osparty.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartyDelta(
	String id,
	String activity,
	String host,
	Integer size,
	List<Member> members,
	String world,
	String layout,
	List<String> neededRoles,
	String description,
	Integer capacity,
	String lootRule,
	Boolean ironmanOnly,
	Boolean privateParty,
	Integer minKillCount,
	Integer minHardModeKillCount,
	Integer invocation,
	Boolean hardMode,
	List<String> requiredRoles,
	String hostRole,
	Boolean learner,
	Boolean teacher) {

	public static PartyDelta diff(Party prev, Party cur) {
		String host = Objects.equals(prev.getHost(), cur.getHost()) ? null : cur.getHost();
		Integer size = prev.getSize() != cur.getSize() ? cur.getSize() : null;
		List<Member> members = Objects.equals(prev.getMembers(), cur.getMembers()) ? null : cur.getMembers();
		String world = Objects.equals(prev.getWorld(), cur.getWorld()) ? null : cur.getWorld();
		String layout = Objects.equals(prev.getLayout(), cur.getLayout()) ? null : cur.getLayout();
		List<String> neededRoles =
			Objects.equals(prev.getNeededRoles(), cur.getNeededRoles()) ? null : cur.getNeededRoles();
		String description =
			Objects.equals(prev.getDescription(), cur.getDescription()) ? null : cur.getDescription();
		Integer capacity = prev.getCapacity() != cur.getCapacity() ? cur.getCapacity() : null;
		String lootRule = Objects.equals(prev.getLootRule(), cur.getLootRule()) ? null : cur.getLootRule();
		Boolean ironmanOnly = prev.isIronmanOnly() != cur.isIronmanOnly() ? cur.isIronmanOnly() : null;
		Boolean privateParty = prev.isPrivateParty() != cur.isPrivateParty() ? cur.isPrivateParty() : null;
		Integer minKillCount = prev.getMinKillCount() != cur.getMinKillCount() ? cur.getMinKillCount() : null;
		Integer minHardModeKillCount =
			prev.getMinHardModeKillCount() != cur.getMinHardModeKillCount() ? cur.getMinHardModeKillCount() : null;
		Integer invocation = prev.getInvocation() != cur.getInvocation() ? cur.getInvocation() : null;
		Boolean hardMode = prev.isHardMode() != cur.isHardMode() ? cur.isHardMode() : null;
		List<String> requiredRoles =
			Objects.equals(prev.getRequiredRoles(), cur.getRequiredRoles()) ? null : cur.getRequiredRoles();
		String hostRole = Objects.equals(prev.getHostRole(), cur.getHostRole()) ? null : cur.getHostRole();
		Boolean learner = prev.isLearner() != cur.isLearner() ? cur.isLearner() : null;
		Boolean teacher = prev.isTeacher() != cur.isTeacher() ? cur.isTeacher() : null;

		if (host == null && size == null && members == null && world == null && layout == null && neededRoles == null
			&& description == null && capacity == null && lootRule == null && ironmanOnly == null && privateParty == null
			&& minKillCount == null && minHardModeKillCount == null && invocation == null && hardMode == null
			&& requiredRoles == null && hostRole == null && learner == null && teacher == null) {
			return null;
		}
		return new PartyDelta(cur.getId(), cur.getActivity(), host, size, members, world, layout, neededRoles, description,
			capacity, lootRule, ironmanOnly, privateParty, minKillCount, minHardModeKillCount, invocation, hardMode,
			requiredRoles, hostRole, learner, teacher);
	}
}

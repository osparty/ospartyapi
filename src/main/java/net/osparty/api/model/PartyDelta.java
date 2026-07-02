package net.osparty.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * A partial update for an existing ad: carries {@code id} and {@code activity} always (the activity
 * lets the broadcaster route/filter without the client needing it), plus ONLY the mutable fields
 * that actually changed. Everything else is null and omitted on the wire, so a size/world tweak ships
 * a handful of bytes instead of the full 30-field {@link Party}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartyDelta(
	String id,
	String activity,
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

	/**
	 * Builds a delta of the fields that differ between {@code prev} and {@code cur}, or returns null
	 * if none of the mutable fields changed. The mutable set is exactly {@link PartyUpdate}'s fields —
	 * the only things that can change after an ad is created.
	 */
	public static PartyDelta diff(Party prev, Party cur) {
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

		if (size == null && members == null && world == null && layout == null && neededRoles == null
			&& description == null && capacity == null && lootRule == null && ironmanOnly == null && privateParty == null
			&& minKillCount == null && minHardModeKillCount == null && invocation == null && hardMode == null
			&& requiredRoles == null && hostRole == null && learner == null && teacher == null) {
			return null;
		}
		return new PartyDelta(cur.getId(), cur.getActivity(), size, members, world, layout, neededRoles, description,
			capacity, lootRule, ironmanOnly, privateParty, minKillCount, minHardModeKillCount, invocation, hardMode,
			requiredRoles, hostRole, learner, teacher);
	}
}

package net.osparty.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdvertisementDelta(
	String id,
	String activity,
	String host,
	/**
	 * Travels with {@code host}, and for the same reason a whole advertisement carries it: a transfer
	 * rewrites the host without touching the member list, so a client that infers the hash from member
	 * zero gets the outgoing host. Absent unless the hash actually changed.
	 */
	Long hostAccountHash,
	Integer size,
	List<Member> members,
	String world,
	String layout,
	List<String> neededRoles,
	String description,
	Integer capacity,
	String lootRule,
	Boolean ironmanOnly,
	Boolean privateAd,
	Integer minKillCount,
	Integer minHardModeKillCount,
	Integer invocation,
	Boolean hardMode,
	String coxScale,
	List<String> requiredRoles,
	String hostRole,
	Boolean learner,
	Boolean teacher,
	/**
	 * The pod the host's live room moved to. Carried on a delta and not only on a whole
	 * advertisement because a host cannot know where its room landed until the live welcome tells
	 * it, which is after it advertised — so the stamp always arrives as a change to an ad the
	 * board already holds.
	 */
	String node) {

	public static AdvertisementDelta diff(Advertisement prev, Advertisement cur) {
		String host = Objects.equals(prev.getHost(), cur.getHost()) ? null : cur.getHost();
		Long hostAccountHash =
			prev.getHostAccountHash() != cur.getHostAccountHash() ? cur.getHostAccountHash() : null;
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
		Boolean privateAd = prev.isPrivateAd() != cur.isPrivateAd() ? cur.isPrivateAd() : null;
		Integer minKillCount = prev.getMinKillCount() != cur.getMinKillCount() ? cur.getMinKillCount() : null;
		Integer minHardModeKillCount =
			prev.getMinHardModeKillCount() != cur.getMinHardModeKillCount() ? cur.getMinHardModeKillCount() : null;
		Integer invocation = prev.getInvocation() != cur.getInvocation() ? cur.getInvocation() : null;
		Boolean hardMode = prev.isHardMode() != cur.isHardMode() ? cur.isHardMode() : null;
		String coxScale = Objects.equals(prev.getCoxScale(), cur.getCoxScale()) ? null : cur.getCoxScale();
		List<String> requiredRoles =
			Objects.equals(prev.getRequiredRoles(), cur.getRequiredRoles()) ? null : cur.getRequiredRoles();
		String hostRole = Objects.equals(prev.getHostRole(), cur.getHostRole()) ? null : cur.getHostRole();
		Boolean learner = prev.isLearner() != cur.isLearner() ? cur.isLearner() : null;
		Boolean teacher = prev.isTeacher() != cur.isTeacher() ? cur.isTeacher() : null;
		String node = Objects.equals(prev.getNode(), cur.getNode()) ? null : cur.getNode();

		if (host == null && hostAccountHash == null && size == null && members == null && world == null
			&& layout == null && neededRoles == null
			&& description == null && capacity == null && lootRule == null && ironmanOnly == null && privateAd == null
			&& minKillCount == null && minHardModeKillCount == null && invocation == null && hardMode == null
			&& coxScale == null && requiredRoles == null && hostRole == null && learner == null && teacher == null
			&& node == null) {
			return null;
		}
		return new AdvertisementDelta(cur.getId(), cur.getActivity(), host, hostAccountHash, size, members, world, layout, neededRoles, description,
			capacity, lootRule, ironmanOnly, privateAd, minKillCount, minHardModeKillCount, invocation, hardMode,
			coxScale, requiredRoles, hostRole, learner, teacher, node);
	}
}

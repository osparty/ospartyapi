package net.osparty.api.service;

import net.osparty.api.model.Member;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class PartyFactory {
	private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
	private static final int CODE_LENGTH = 6;
	private static final SecureRandom RANDOM = new SecureRandom();

	private PartyFactory() {
	}

	public static Party fromRequest(PartyRequest request, String id, String inviteCode, long now) {
		Party party = new Party();
		party.setId(id);
		party.setActivity(request.activity());
		party.setHost(request.host());
		party.setDescription(request.description());
		party.setCapacity(request.capacity());
		party.setWorld(request.world());
		party.setMinKillCount(request.minKillCount());
		party.setMinHardModeKillCount(request.minHardModeKillCount());
		party.setPassphrase(request.passphrase());
		party.setCreatedAt(now);
		party.setPrivateParty(request.privateParty());
		party.setInviteCode(inviteCode);
		party.setLootRule(normalizeLootRule(request.lootRule()));
		party.setIronmanOnly(request.ironmanOnly());
		party.setHostAccountType(request.hostAccountType());
		party.setHardMode(request.hardMode());
		party.setInvocation(request.invocation());
		party.setRequiredRoles(request.requiredRoles());
		party.setHostRole(request.hostRole());
		party.setNeededRoles(initialNeededRoles(request.requiredRoles(), request.hostRole()));
		party.setLearner(request.learner());
		party.setTeacher(request.teacher());
		party.setSize(1);
		List<Member> members = new ArrayList<>();
		if (request.host() != null) {
			members.add(new Member(request.host(), request.hostAccountHash()));
		}
		party.setMembers(members);
		return party;
	}

	private static List<String> initialNeededRoles(List<String> requiredRoles, String hostRole) {
		if (requiredRoles == null || requiredRoles.isEmpty()) {
			return requiredRoles;
		}
		List<String> needed = new ArrayList<>(requiredRoles);
		if (hostRole != null) {
			needed.remove(hostRole);
		}
		return needed;
	}

	public static List<String> parseRoles(String csv) {
		if (csv == null || csv.isBlank()) {
			return null;
		}
		List<String> roles = new ArrayList<>();
		for (String part : csv.split(",")) {
			String role = part.trim();
			if (!role.isEmpty()) {
				roles.add(role);
			}
		}
		return roles;
	}

	public static boolean applyUpdate(Party party, PartyUpdate patch) {
		if (patch == null) {
			return false;
		}
		boolean changed = false;
		if (patch.getSize() != null && patch.getSize() > 0 && patch.getSize() != party.getSize()) {
			party.setSize(patch.getSize());
			changed = true;
		}
		// Roster: the host advertises the live members (host first) so search clients can
		// block/favourite-match any member. Only replace when it actually changed.
		if (patch.getMembers() != null && !patch.getMembers().isEmpty()
			&& !patch.getMembers().equals(party.getMembers())) {
			party.setMembers(patch.getMembers());
			changed = true;
		}
		if (patch.getWorld() != null && !patch.getWorld().isBlank() && !patch.getWorld().equals(party.getWorld())) {
			party.setWorld(patch.getWorld());
			changed = true;
		}
		if (patch.getLayout() != null && !patch.getLayout().isBlank() && !patch.getLayout().equals(party.getLayout())) {
			party.setLayout(patch.getLayout());
			changed = true;
		}
		if (patch.getNeededRoles() != null && !patch.getNeededRoles().equals(party.getNeededRoles())) {
			party.setNeededRoles(patch.getNeededRoles());
			changed = true;
		}
		if (patch.getDescription() != null && !patch.getDescription().equals(party.getDescription())) {
			party.setDescription(patch.getDescription());
			changed = true;
		}
		if (patch.getCapacity() != null && patch.getCapacity() > 0 && patch.getCapacity() != party.getCapacity()) {
			party.setCapacity(patch.getCapacity());
			changed = true;
		}
		if (patch.getLootRule() != null) {
			String lootRule = normalizeLootRule(patch.getLootRule());
			if (!lootRule.equals(party.getLootRule())) {
				party.setLootRule(lootRule);
				changed = true;
			}
		}
		if (patch.getIronmanOnly() != null && patch.getIronmanOnly() != party.isIronmanOnly()) {
			party.setIronmanOnly(patch.getIronmanOnly());
			changed = true;
		}
		if (patch.getPrivateParty() != null && patch.getPrivateParty() != party.isPrivateParty()) {
			party.setPrivateParty(patch.getPrivateParty());
			changed = true;
		}
		if (patch.getMinKillCount() != null && patch.getMinKillCount() != party.getMinKillCount()) {
			party.setMinKillCount(patch.getMinKillCount());
			changed = true;
		}
		if (patch.getMinHardModeKillCount() != null
			&& patch.getMinHardModeKillCount() != party.getMinHardModeKillCount()) {
			party.setMinHardModeKillCount(patch.getMinHardModeKillCount());
			changed = true;
		}
		if (patch.getInvocation() != null && patch.getInvocation() != party.getInvocation()) {
			party.setInvocation(patch.getInvocation());
			changed = true;
		}
		if (patch.getHardMode() != null && patch.getHardMode() != party.isHardMode()) {
			party.setHardMode(patch.getHardMode());
			changed = true;
		}
		// Roles: when the required composition or host role changes, re-seed neededRoles
		// from it (the live host's heartbeat then keeps it accurate against admitted members).
		boolean rolesChanged = false;
		if (patch.getRequiredRoles() != null && !patch.getRequiredRoles().equals(party.getRequiredRoles())) {
			party.setRequiredRoles(patch.getRequiredRoles());
			changed = true;
			rolesChanged = true;
		}
		if (patch.getHostRole() != null && !patch.getHostRole().equals(party.getHostRole())) {
			party.setHostRole(patch.getHostRole());
			changed = true;
			rolesChanged = true;
		}
		if (rolesChanged) {
			party.setNeededRoles(initialNeededRoles(party.getRequiredRoles(), party.getHostRole()));
		}
		if (patch.getLearner() != null && patch.getLearner() != party.isLearner()) {
			party.setLearner(patch.getLearner());
			changed = true;
		}
		if (patch.getTeacher() != null && patch.getTeacher() != party.isTeacher()) {
			party.setTeacher(patch.getTeacher());
			changed = true;
		}
		return changed;
	}

	public static String newInviteCode() {
		StringBuilder sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
		}
		return sb.toString();
	}

	public static String normalizeInviteCode(String code) {
		return code == null ? null : code.trim().toUpperCase();
	}

	private static String normalizeLootRule(String lootRule) {
		if (lootRule == null || lootRule.isBlank()) {
			return "UNSPECIFIED";
		}
		return lootRule.trim().toUpperCase();
	}

	public static boolean hostKeyAuthorized(String stored, String supplied) {
		if (stored == null || stored.isBlank()) {
			return true;
		}
		if (supplied == null) {
			return false;
		}
		// Constant-time compare so a mismatch can't be timed out character by character.
		return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
	}

	public static boolean sameHost(String a, String b) {
		return a != null && b != null && normalizeHost(a).equals(normalizeHost(b));
	}

	public static String normalizeHost(String host) {
		return host.replace(' ', ' ').trim().toLowerCase();
	}
}

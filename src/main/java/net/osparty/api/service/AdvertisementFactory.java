package net.osparty.api.service;

import net.osparty.api.model.Member;
import net.osparty.api.model.Advertisement;
import net.osparty.api.model.AdvertisementRequest;
import net.osparty.api.model.AdvertisementUpdate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class AdvertisementFactory {
	private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
	private static final int CODE_LENGTH = 6;
	private static final SecureRandom RANDOM = new SecureRandom();

	private AdvertisementFactory() {
	}

	public static Advertisement fromRequest(AdvertisementRequest request, String id, String inviteCode, long now) {
		Advertisement ad = new Advertisement();
		ad.setId(id);
		ad.setActivity(request.activity());
		ad.setHost(request.host());
		ad.setHostAccountHash(request.hostAccountHash());
		ad.setDescription(request.description());
		ad.setCapacity(request.capacity());
		ad.setWorld(request.world());
		ad.setMinKillCount(request.minKillCount());
		ad.setMinHardModeKillCount(request.minHardModeKillCount());
		ad.setPassphrase(request.passphrase());
		ad.setCreatedAt(now);
		ad.setPrivateAd(request.privateAd());
		ad.setInviteCode(inviteCode);
		ad.setLootRule(normalizeLootRule(request.lootRule()));
		ad.setIronmanOnly(request.ironmanOnly());
		ad.setHostAccountType(request.hostAccountType());
		ad.setHardMode(request.hardMode());
		ad.setInvocation(request.invocation());
		ad.setCoxScale(request.coxScale());
		ad.setRequiredRoles(request.requiredRoles());
		ad.setHostRole(request.hostRole());
		ad.setNeededRoles(initialNeededRoles(request.requiredRoles(), request.hostRole()));
		ad.setLearner(request.learner());
		ad.setTeacher(request.teacher());
		ad.setSize(1);
		List<Member> members = new ArrayList<>();
		if (request.host() != null) {
			members.add(new Member(request.host(), request.hostAccountHash()));
		}
		ad.setMembers(members);
		return ad;
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

	public static boolean applyUpdate(Advertisement ad, AdvertisementUpdate patch) {
		return applyUpdate(ad, patch, System.currentTimeMillis());
	}

	/** As {@link #applyUpdate(Advertisement, AdvertisementUpdate)}, with the clock the caller is working to. */
	public static boolean applyUpdate(Advertisement ad, AdvertisementUpdate patch, long now) {
		if (patch == null) {
			return false;
		}
		boolean changed = false;
		boolean wasFull = isFull(ad);
		if (patch.getSize() != null && patch.getSize() > 0 && patch.getSize() != ad.getSize()) {
			ad.setSize(patch.getSize());
			changed = true;
		}
		if (patch.getMembers() != null && !patch.getMembers().isEmpty()) {
			List<Member> merged = mergeKnownHashes(ad.getMembers(), patch.getMembers());
			if (!merged.equals(ad.getMembers())) {
				ad.setMembers(merged);
				changed = true;
			}
		}
		if (patch.getNode() != null && !patch.getNode().isBlank() && !patch.getNode().equals(ad.getNode())) {
			ad.setNode(patch.getNode());
			changed = true;
		}
		if (patch.getWorld() != null && !patch.getWorld().isBlank() && !patch.getWorld().equals(ad.getWorld())) {
			ad.setWorld(patch.getWorld());
			changed = true;
		}
		if (patch.getLayout() != null && !patch.getLayout().isBlank() && !patch.getLayout().equals(ad.getLayout())) {
			ad.setLayout(patch.getLayout());
			changed = true;
		}
		if (patch.getNeededRoles() != null && !patch.getNeededRoles().equals(ad.getNeededRoles())) {
			ad.setNeededRoles(patch.getNeededRoles());
			changed = true;
		}
		if (patch.getDescription() != null && !patch.getDescription().equals(ad.getDescription())) {
			ad.setDescription(patch.getDescription());
			changed = true;
		}
		if (patch.getCapacity() != null && patch.getCapacity() > 0 && patch.getCapacity() != ad.getCapacity()) {
			ad.setCapacity(patch.getCapacity());
			changed = true;
		}
		if (patch.getLootRule() != null) {
			String lootRule = normalizeLootRule(patch.getLootRule());
			if (!lootRule.equals(ad.getLootRule())) {
				ad.setLootRule(lootRule);
				changed = true;
			}
		}
		if (patch.getIronmanOnly() != null && patch.getIronmanOnly() != ad.isIronmanOnly()) {
			ad.setIronmanOnly(patch.getIronmanOnly());
			changed = true;
		}
		if (patch.getPrivateAd() != null && patch.getPrivateAd() != ad.isPrivateAd()) {
			ad.setPrivateAd(patch.getPrivateAd());
			changed = true;
		}
		if (patch.getMinKillCount() != null && patch.getMinKillCount() != ad.getMinKillCount()) {
			ad.setMinKillCount(patch.getMinKillCount());
			changed = true;
		}
		if (patch.getMinHardModeKillCount() != null
			&& patch.getMinHardModeKillCount() != ad.getMinHardModeKillCount()) {
			ad.setMinHardModeKillCount(patch.getMinHardModeKillCount());
			changed = true;
		}
		if (patch.getInvocation() != null && patch.getInvocation() != ad.getInvocation()) {
			ad.setInvocation(patch.getInvocation());
			changed = true;
		}
		if (patch.getHardMode() != null && patch.getHardMode() != ad.isHardMode()) {
			ad.setHardMode(patch.getHardMode());
			changed = true;
		}
		// Scale is host-editable and clearable (an empty string removes it), so merge on any
		// non-null difference rather than guarding against blank like world/layout.
		if (patch.getCoxScale() != null && !patch.getCoxScale().equals(ad.getCoxScale())) {
			ad.setCoxScale(patch.getCoxScale());
			changed = true;
		}
		// Roles: when the required composition or host role changes, re-seed neededRoles
		// from it (the live host's heartbeat then keeps it accurate against admitted members).
		boolean rolesChanged = false;
		if (patch.getRequiredRoles() != null && !patch.getRequiredRoles().equals(ad.getRequiredRoles())) {
			ad.setRequiredRoles(patch.getRequiredRoles());
			changed = true;
			rolesChanged = true;
		}
		if (patch.getHostRole() != null && !patch.getHostRole().equals(ad.getHostRole())) {
			ad.setHostRole(patch.getHostRole());
			changed = true;
			rolesChanged = true;
		}
		if (rolesChanged) {
			ad.setNeededRoles(initialNeededRoles(ad.getRequiredRoles(), ad.getHostRole()));
		}
		if (patch.getLearner() != null && patch.getLearner() != ad.isLearner()) {
			ad.setLearner(patch.getLearner());
			changed = true;
		}
		if (patch.getTeacher() != null && patch.getTeacher() != ad.isTeacher()) {
			ad.setTeacher(patch.getTeacher());
			changed = true;
		}
		if (wasFull && !isFull(ad)) {
			// A party that has stopped being full is looking again, and it is looking as of now. Its clock
			// has been running since it was created, so without this a team that filled up, raided for three
			// hours and lost someone advertises the seat as "searching 3h" — and is dimmed as stale next to
			// parties that have been looking for a tenth as long.
			ad.setCreatedAt(now);
			changed = true;
		}
		return changed;
	}

	/** Whether {@code ad} has no room left. An uncapped party is never full: there is always room in it. */
	private static boolean isFull(Advertisement ad) {
		return ad.getCapacity() > 0 && ad.getSize() >= ad.getCapacity();
	}

	private static List<Member> mergeKnownHashes(List<Member> stored, List<Member> incoming) {
		if (stored == null || stored.isEmpty()) {
			return incoming;
		}
		List<Member> out = new ArrayList<>(incoming.size());
		for (Member member : incoming) {
			if (member != null && member.getAccountHash() == 0 && member.getName() != null) {
				Member known = findByName(stored, member.getName());
				if (known != null && known.getAccountHash() != 0) {
					out.add(new Member(member.getName(), known.getAccountHash()));
					continue;
				}
			}
			out.add(member);
		}
		return out;
	}

	/**
	 * The account hash the ad knows for {@code host}, or 0 when no admitted member matches by
	 * name or that member never reported one. Used on host transfer to keep
	 * {@code Advertisement.hostAccountHash} pointing at whoever actually runs the ad.
	 */
	public static long accountHashOf(Advertisement ad, String host) {
		if (ad.getMembers() == null || host == null) {
			return 0;
		}
		Member member = findByName(ad.getMembers(), host);
		return member == null ? 0 : member.getAccountHash();
	}

	private static Member findByName(List<Member> members, String name) {
		for (Member member : members) {
			if (member != null && member.getName() != null
				&& normalizeHost(member.getName()).equals(normalizeHost(name))) {
				return member;
			}
		}
		return null;
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

	/**
	 * Whether {@code supplied} authorises host-only changes to an ad whose stored key is {@code stored}.
	 *
	 * <p>An ad with no stored key used to authorise everyone, on the reasoning that there was nothing to
	 * check against. But the repository only ever stores non-blank keys, so a missing one does not mean
	 * "this ad is unprotected" -- it means the write that should have saved the key did not, and the ad is
	 * then editable and deletable by anyone who learns its id. Refusing instead costs its host the ability to
	 * edit that one ad, which is recoverable by re-hosting; the alternative is not.
	 */
	public static boolean hostKeyAuthorized(String stored, String supplied) {
		if (stored == null || stored.isBlank()) {
			return false;
		}
		if (supplied == null) {
			return false;
		}
		return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
	}

	public static boolean sameHost(String a, String b) {
		return a != null && b != null && normalizeHost(a).equals(normalizeHost(b));
	}

	/**
	 * The canonical identity form for an OSRS name: Jagex renders spaces as a non-breaking space,
	 * so fold that to a plain space before trimming and lowercasing. Everything that keys on a
	 * player -- the Redis {@code partyhost:} index, socket identity, ad bans -- goes through here,
	 * or the same player ends up with two identities.
	 */
	public static String normalizeHost(String host) {
		return host.replace(' ', ' ').trim().toLowerCase();
	}
}

package net.osparty.api.repository;

import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
import net.osparty.api.service.PartyFactory;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class FakePartyRepository implements PartyRepository {
	private final Map<String, Party> parties = new ConcurrentHashMap<>();
	private final Map<String, String> hostKeys = new ConcurrentHashMap<>();
	private final AtomicLong idSequence = new AtomicLong(1000);

	@Override
	public List<Party> list(String activity) {
		return parties.values().stream()
			.filter(p -> !p.isPrivateParty())
			.filter(p -> activity == null || activity.isBlank() || activity.equals(p.getActivity()))
			.sorted(Comparator.comparingLong(Party::getCreatedAt).reversed())
			.collect(Collectors.toList());
	}

	@Override
	public Optional<Party> findByInviteCode(String code) {
		String normalized = PartyFactory.normalizeInviteCode(code);
		if (normalized == null) {
			return Optional.empty();
		}
		return parties.values().stream()
			.filter(p -> normalized.equals(p.getInviteCode()))
			.findFirst();
	}

	@Override
	public Optional<Party> findByHost(String host) {
		if (host == null) {
			return Optional.empty();
		}
		return parties.values().stream()
			.filter(p -> PartyFactory.sameHost(p.getHost(), host))
			.findFirst();
	}

	@Override
	public Party create(PartyRequest request, String hostKey) {
		long now = System.currentTimeMillis();
		Party party = PartyFactory.fromRequest(request, nextId(), uniqueInviteCode(), now);

		parties.values().removeIf(p -> {
			if (PartyFactory.sameHost(p.getHost(), request.host())) {
				hostKeys.remove(p.getId());
				return true;
			}
			return false;
		});

		parties.put(party.getId(), party);
		if (hostKey != null && !hostKey.isBlank()) {
			hostKeys.put(party.getId(), hostKey);
		}
		return party;
	}

	@Override
	public Authorization authorize(String id, String hostKey) {
		if (!parties.containsKey(id)) {
			return Authorization.NOT_FOUND;
		}
		return PartyFactory.hostKeyAuthorized(hostKeys.get(id), hostKey)
			? Authorization.OK : Authorization.FORBIDDEN;
	}

	@Override
	public Optional<Party> update(String id, PartyUpdate patch) {
		Party party = parties.get(id);
		if (party == null) {
			return Optional.empty();
		}
		PartyFactory.applyUpdate(party, patch);
		return Optional.of(party);
	}

	@Override
	public Optional<Party> delete(String id) {
		hostKeys.remove(id);
		return Optional.ofNullable(parties.remove(id));
	}

	private String nextId() {
		return String.valueOf(idSequence.getAndIncrement());
	}

	private String uniqueInviteCode() {
		String code;
		do {
			code = PartyFactory.newInviteCode();
		}
		while (findByInviteCode(code).isPresent());
		return code;
	}
}

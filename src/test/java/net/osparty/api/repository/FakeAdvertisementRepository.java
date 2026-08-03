package net.osparty.api.repository;

import net.osparty.api.model.Advertisement;
import net.osparty.api.model.AdvertisementRequest;
import net.osparty.api.model.AdvertisementUpdate;
import net.osparty.api.service.AdvertisementFactory;
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
public class FakeAdvertisementRepository implements AdvertisementRepository {
	private final Map<String, Advertisement> parties = new ConcurrentHashMap<>();
	private final Map<String, String> hostKeys = new ConcurrentHashMap<>();
	private final AtomicLong idSequence = new AtomicLong(1000);
	private final AtomicLong revisions = new AtomicLong();

	@Override
	public List<Advertisement> list(String activity) {
		return parties.values().stream()
			.filter(p -> !p.isPrivateAd())
			.filter(p -> activity == null || activity.isBlank() || activity.equals(p.getActivity()))
			.sorted(Comparator.comparingLong(Advertisement::getCreatedAt).reversed())
			.collect(Collectors.toList());
	}

	@Override
	public int advertisementCount() {
		return parties.size();
	}
	
	@Override
	public Optional<Advertisement> findById(String id) {
		return id == null ? Optional.empty() : Optional.ofNullable(parties.get(id));
	}

	@Override
	public Optional<Advertisement> findByInviteCode(String code) {
		String normalized = AdvertisementFactory.normalizeInviteCode(code);
		if (normalized == null) {
			return Optional.empty();
		}
		return parties.values().stream()
			.filter(p -> normalized.equals(p.getInviteCode()))
			.findFirst();
	}

	@Override
	public Optional<Advertisement> findByHost(String host) {
		if (host == null) {
			return Optional.empty();
		}
		return parties.values().stream()
			.filter(p -> AdvertisementFactory.sameHost(p.getHost(), host))
			.findFirst();
	}

	@Override
	public long nextRevision() {
		return revisions.incrementAndGet();
	}

	@Override
	public Advertisement create(AdvertisementRequest request, String hostKey) {
		long now = System.currentTimeMillis();
		Advertisement party = AdvertisementFactory.fromRequest(request, nextId(), uniqueInviteCode(), now);
		party.setSeq(nextRevision());

		parties.values().removeIf(p -> {
			if (AdvertisementFactory.sameHost(p.getHost(), request.host())) {
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
		return AdvertisementFactory.hostKeyAuthorized(hostKeys.get(id), hostKey)
			? Authorization.OK : Authorization.FORBIDDEN;
	}

	@Override
	public Optional<Advertisement> update(String id, AdvertisementUpdate patch) {
		Advertisement party = parties.get(id);
		if (party == null) {
			return Optional.empty();
		}
		if (AdvertisementFactory.applyUpdate(party, patch)) {
			// Only a real edit, matching the Redis repository: a TTL touch must not look like a change.
			party.setSeq(nextRevision());
		}
		return Optional.of(party);
	}

	@Override
	public Optional<Advertisement> transferHost(String id, String newHost, String newHostAccountType, String newKey) {
		Advertisement party = parties.get(id);
		if (party == null) {
			return Optional.empty();
		}
		party.setHost(newHost);
		party.setHostAccountHash(AdvertisementFactory.accountHashOf(party, newHost));
		party.setHostAccountType(newHostAccountType == null || newHostAccountType.isBlank()
			? "NORMAL" : newHostAccountType);
		party.setSeq(nextRevision());
		hostKeys.put(id, newKey);
		return Optional.of(party);
	}

	@Override
	public Optional<Advertisement> attachVoiceChannel(String id, String channelId, String inviteUrl) {
		Advertisement party = parties.get(id);
		if (party == null) {
			return Optional.empty();
		}
		party.setDiscordChannelId(channelId);
		party.setDiscordInviteUrl(inviteUrl);
		party.setSeq(nextRevision());
		return Optional.of(party);
	}

	@Override
	public Optional<Advertisement> delete(String id) {
		hostKeys.remove(id);
		return Optional.ofNullable(parties.remove(id));
	}

	private String nextId() {
		return String.valueOf(idSequence.getAndIncrement());
	}

	private String uniqueInviteCode() {
		String code;
		do {
			code = AdvertisementFactory.newInviteCode();
		}
		while (findByInviteCode(code).isPresent());
		return code;
	}
}

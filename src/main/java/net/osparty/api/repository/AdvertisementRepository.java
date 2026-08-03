package net.osparty.api.repository;

import net.osparty.api.model.Advertisement;
import net.osparty.api.model.AdvertisementRequest;
import net.osparty.api.model.AdvertisementUpdate;
import net.osparty.api.service.AdvertisementFactory;
import java.util.List;
import java.util.Optional;

public interface AdvertisementRepository {
	List<Advertisement> list(String activity);

	int advertisementCount();

	Optional<Advertisement> findById(String id);

	Optional<Advertisement> findByInviteCode(String code);

	Optional<Advertisement> findByHost(String host);

	Advertisement create(AdvertisementRequest request, String hostKey);

	Optional<Advertisement> update(String id, AdvertisementUpdate patch);

	default Optional<Advertisement> heartbeat(String id, Integer size, String world, String layout, String roles) {
		AdvertisementUpdate patch = new AdvertisementUpdate();
		patch.setSize(size);
		patch.setWorld(world);
		patch.setLayout(layout);
		patch.setNeededRoles(AdvertisementFactory.parseRoles(roles));
		return update(id, patch);
	}

	Optional<Advertisement> delete(String id);

	/**
	 * The next cluster-wide revision, for a change that has no advertisement left to stamp — a removal.
	 *
	 * <p>Ads carry their own {@link Advertisement#getSeq()}; a deleted one carries nothing, so its tombstone needs a
	 * number from the same sequence to be ordered against everything else a resuming client might have
	 * missed.
	 */
	long nextRevision();

	/**
	 * Reassign the ad to {@code newHost} in place, re-keying its credential to {@code newKey}.
	 * {@code newHostAccountType} re-stamps the badge shown beside the host on the board; a null or
	 * blank one is stored as {@code NORMAL} rather than left absent, so the change is expressible as a
	 * delta and never leaves the outgoing host's badge behind.
	 */
	Optional<Advertisement> transferHost(String id, String newHost, String newHostAccountType, String newKey);

	Optional<Advertisement> attachVoiceChannel(String id, String channelId, String inviteUrl);

	Authorization authorize(String id, String hostKey);

	enum Authorization {
		OK, NOT_FOUND, FORBIDDEN
	}
}

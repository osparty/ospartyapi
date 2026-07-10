package net.osparty.api.repository;

import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
import net.osparty.api.service.PartyFactory;
import java.util.List;
import java.util.Optional;

public interface PartyRepository {
	List<Party> list(String activity);

	Optional<Party> findById(String id);

	Optional<Party> findByInviteCode(String code);

	Optional<Party> findByHost(String host);

	Party create(PartyRequest request, String hostKey);

	Optional<Party> update(String id, PartyUpdate patch);

	default Optional<Party> heartbeat(String id, Integer size, String world, String layout, String roles) {
		PartyUpdate patch = new PartyUpdate();
		patch.setSize(size);
		patch.setWorld(world);
		patch.setLayout(layout);
		patch.setNeededRoles(PartyFactory.parseRoles(roles));
		return update(id, patch);
	}

	Optional<Party> delete(String id);

	/**
	 * Reassign an existing ad to a new host in place, preserving the party id, invite code, passphrase
	 * and any attached Discord channel. Moves the host index from the old host to {@code newHost} and
	 * replaces the stored host credential with {@code newKey} (so the previous host's key stops
	 * authorising). Returns the updated party, or empty if it no longer exists. The new host's next
	 * heartbeat refreshes the members roster (host first); this only swaps the host name + credential.
	 */
	Optional<Party> transferHost(String id, String newHost, String newKey);

	/**
	 * Attach a provisioned Discord voice channel (id + invite URL) to an existing party and persist
	 * it. Returns the updated party, or empty if the party no longer exists. Unlike {@link #update},
	 * these fields are not part of {@link PartyUpdate}, so they never travel the ad-delta firehose.
	 */
	Optional<Party> attachVoiceChannel(String id, String channelId, String inviteUrl);

	Authorization authorize(String id, String hostKey);

	enum Authorization {
		OK, NOT_FOUND, FORBIDDEN
	}
}

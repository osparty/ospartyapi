package net.osparty.api.repository;

import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
import net.osparty.api.service.PartyFactory;
import java.util.List;
import java.util.Optional;

public interface PartyRepository {
	List<Party> list(String activity);

	int partyCount();

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

	Optional<Party> transferHost(String id, String newHost, String newKey);

	Optional<Party> attachVoiceChannel(String id, String channelId, String inviteUrl);

	Authorization authorize(String id, String hostKey);

	enum Authorization {
		OK, NOT_FOUND, FORBIDDEN
	}
}

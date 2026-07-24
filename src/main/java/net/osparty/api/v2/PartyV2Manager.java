package net.osparty.api.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Owns this node's live party rooms in memory (PARTY_V2_MIGRATION.md §11). The top-level map is
 * concurrent and each {@link LivePartyRoom} guards its own state, so rooms are independent.
 *
 * <p>P1 is single-node: every room created here is owned by this node (the one the host connected to).
 * P2 adds Redis ownership, node-hint routing and heartbeat failover.
 */
@Component
@ConditionalOnProperty(name = "app.party-v2.enabled", havingValue = "true")
public class PartyV2Manager {
	private final ObjectMapper mapper;
	private final Map<String, LivePartyRoom> rooms = new ConcurrentHashMap<>();
	private final AtomicLong memberIds = new AtomicLong();

	public PartyV2Manager(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/** A fresh, process-unique member id assigned to a connecting client. */
	long nextMemberId() {
		return memberIds.incrementAndGet();
	}

	/** The room hosting {@code id}, creating it if this is the host's first {@code host} frame. */
	LivePartyRoom hostRoom(String id, String activityId) {
		return rooms.computeIfAbsent(id, k -> new LivePartyRoom(id, activityId, mapper));
	}

	/** The existing room for {@code id}, or null if none is hosted here. */
	LivePartyRoom room(String id) {
		return rooms.get(id);
	}

	/** Discard a room once it's empty or its host has left. */
	void discard(String id) {
		rooms.remove(id);
	}

	public int roomCount() {
		return rooms.size();
	}
}

package net.osparty.api.v2;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * This node's identity for Party V2 ownership + node-hint routing (PARTY_V2_MIGRATION.md §3.2/§4.1).
 *
 * <p>The {@code nodeId} is the routing key: it is stamped into {@code pv2:owner:{room}} on claim, returned in
 * {@code redirect} frames, and travels in the {@code /n/{nodeId}/api/ws} URL so the gateway can
 * forward a joiner to the owning pod. Injected from {@code app.party-v2.node-id} / {@code POD_NAME} when
 * present (stable across restarts of the same pod), otherwise a random per-process id.
 */
@Component
public class NodeIdentity {
	private final String nodeId;

	@Autowired
	public NodeIdentity(@Value("${app.party-v2.node-id:${POD_NAME:}}") String configured) {
		this.nodeId = (configured == null || configured.isBlank())
			? UUID.randomUUID().toString().substring(0, 12)
			: configured.trim();
	}

	/** Test/fixed-id constructor. */
	public NodeIdentity(String nodeId, boolean fixed) {
		this.nodeId = nodeId;
	}

	public String nodeId() {
		return nodeId;
	}
}

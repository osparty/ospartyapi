package net.osparty.api.web.ws;

public interface PresenceRegistry {
	/** Publish this node's count and return the cluster-wide total. */
	int recordAndTotal(int localCount);
}

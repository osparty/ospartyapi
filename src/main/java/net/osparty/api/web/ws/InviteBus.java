package net.osparty.api.web.ws;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

/**
 * Routes a party invite to its target's live WebSocket, wherever in the cluster that connection is.
 * Session→node membership is per-instance and in-memory, so with multiple API replicas the target is
 * usually connected to a different node than the inviter; this bus bridges that gap.
 */
public interface InviteBus {
	/**
	 * Register the callback that delivers a pre-serialised {@code invited} frame to a target connected to
	 * THIS node. Arguments are the normalised target name and the frame JSON; returns true if delivered.
	 */
	void setLocalDelivery(BiPredicate<String, String> localDelivery);

	/**
	 * Ask the cluster to deliver {@code invitedFrameJson} to {@code normalizedTarget}. The returned future
	 * completes true if some node delivered it to a live connection, false if the target is offline.
	 */
	CompletableFuture<Boolean> dispatch(String normalizedTarget, String invitedFrameJson);
}

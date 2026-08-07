package net.osparty.api.web.ws;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;
import java.util.function.ToIntBiFunction;

/**
 * Reaches an account's signed-in connections wherever in the cluster they are.
 *
 * <p><b>Why this has to exist.</b> Coupling asks one machine to display a code and another to repeat it, and
 * the two machines belong to the same person -- but nothing puts them on the same replica. Session membership
 * is a plain in-memory map per instance, there is no session affinity at the ingress, and the node-hint route
 * ({@code /n/{nodeId}}) covers live party rooms rather than the board socket. With three replicas in
 * production two devices share a pod about a third of the time, so a sweep of the local map missed the other
 * machine in roughly two cases out of three and coupling reported itself unavailable while the user was
 * looking straight at the device that should have shown the code.
 *
 * <p>The shape is {@link InviteBus}'s, for the same reason and with the same trade: publish, let every node
 * answer for the connections it holds, and settle on the acks within a short window. A node that is slow or
 * gone simply does not answer, which reads as "no device there" -- the safe direction, since the client's
 * fallback is the recovery routes rather than a lockout.
 *
 * <p><b>Thread contract.</b> The futures returned here complete on a thread it is safe to block on.
 * Implementations that settle from a shared thread -- a pub/sub listener, a timeout scheduler -- must hand
 * off before completing, because callers hang real work off these answers: deciding which recovery routes to
 * offer queries Postgres twice. Putting that on the listener that also carries invite acks would make one
 * feature's latency another's outage. Stated here rather than left to each caller to remember.
 */
public interface CouplingBus {
	/**
	 * Register how this node answers for its own connections.
	 *
	 * @param online given an account hash, whether this node holds a signed-in connection for it
	 * @param deliver given an account hash and a code, shows the code on this node's signed-in connections
	 *     for that account and returns how many were reached
	 */
	void setLocalHandlers(LongPredicate online, ToIntBiFunction<Long, String> deliver);

	/**
	 * Is any signed-in connection for this account open anywhere?
	 *
	 * <p>Asked before a code is minted, so that a client can be told whether the coupling route is worth
	 * offering without anything appearing on anyone's screen. That ordering is what stops naming an account
	 * hash from being a way to put a dialog in front of its owner.
	 */
	CompletableFuture<Boolean> anyDeviceOnline(long accountHash);

	/**
	 * Show {@code code} on every signed-in connection for this account, cluster-wide.
	 *
	 * <p>Every one of them, not just the first: the person may be sitting at any of their machines, and the
	 * code is worthless to anybody who cannot already see one of those screens.
	 *
	 * @return how many connections were shown it; zero means nobody can read it.
	 */
	CompletableFuture<Integer> deliverCode(long accountHash, String code);
}

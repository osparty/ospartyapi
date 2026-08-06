package net.osparty.api.service;

import java.time.Duration;
import java.util.Optional;

/**
 * The coupling code an account is currently waiting on, while it waits.
 *
 * <p><b>Why this is not a field on the service.</b> The machine displaying the code and the machine typing
 * it in are two connections, and with three replicas behind one ingress they routinely land on different
 * pods. An in-memory map works on a single node and fails in production in the least helpful way: the code
 * appears on one screen and the pod handling the other screen has never heard of it.
 *
 * <p>One pending request per account, enforced by the store rather than by a check-then-write in the caller
 * -- two challengers arriving together would otherwise both find nothing and both write, and the second
 * would overwrite the code the first had already put on the user's screen.
 *
 * <p>Expiry is the store's job too. The first version kept its own timestamps and only cleared an entry when
 * something looked at it, so a request that was started and abandoned sat there forever and locked the
 * account out of coupling for good -- the timeout that was meant to be the safe outcome. A TTL cannot forget
 * to run.
 */
public interface CouplingCodeStore {
	/**
	 * How long a code is good for. Long enough to walk to another machine and type six digits; short enough
	 * that a code left on screen is not a standing invitation.
	 */
	Duration TTL = Duration.ofMinutes(5);

	/** The code an account is waiting on, and the one connection allowed to spend it. */
	record Pending(String code, String challengerSessionId) {
	}

	/**
	 * Record a pending coupling, unless the account already has one.
	 *
	 * @return true if this request is now the pending one; false if another was already in flight.
	 */
	boolean putIfAbsent(long accountHash, String code, String challengerSessionId);

	/** The pending request for an account, or empty when there is none or it has expired. */
	Optional<Pending> get(long accountHash);

	/** Drop a pending request: spent, refused, or abandoned. */
	void remove(long accountHash);
}

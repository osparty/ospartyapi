package net.osparty.api.party.netty;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ceilings on how fast a connection may talk and how often an address may connect.
 *
 * <p>There were none. The only limit anywhere on this socket was on advertisement reports, and the frame
 * loop itself would carry whatever a client sent it -- so one client could hold a room's lock busy, or a
 * script could open sockets in a loop, and nothing in the service would notice or say so.
 *
 * <p><b>It does not reject anything yet.</b> Every breach is counted and logged and the frame is handled as
 * before, because there is no measurement of what real traffic looks like: the numbers below are a guess,
 * and a guess that is enforced is a guess that disconnects players. Run it in this mode, read
 * {@code osparty.socket.ratelimit.*} off the dashboard, set the ceilings from what is actually there, and
 * then turn {@code app.socket.rate-limit.enforce} on. The call sites already do the right thing when it is:
 * they check {@link #enforcing()} rather than assuming.
 *
 * <p>Windows are fixed rather than sliding, which lets a burst spanning a boundary through at up to twice
 * the ceiling. That is the right trade here -- the cost is one map entry and two longs per connection, and
 * this has to run on every frame of every party in the process.
 */
public final class SocketRateLimiter {
	private static final Logger log = LoggerFactory.getLogger(SocketRateLimiter.class);

	/**
	 * A party heartbeats every few seconds and sends a live update per tick, so a member in a busy raid sits
	 * in the low tens per window. This leaves a wide margin over that: the point of the first pass is to
	 * catch something pathological, not to trim the tail.
	 */
	static final int FRAMES_PER_WINDOW = 600;
	static final long FRAME_WINDOW_MS = 10_000;

	/** A client reconnects on a backoff, and a party re-forming reconnects everyone at once behind one NAT. */
	static final int CONNECTS_PER_WINDOW = 60;
	static final long CONNECT_WINDOW_MS = 60_000;

	/** Stop tracking addresses if something is cycling through them, rather than growing without bound. */
	private static final int MAX_TRACKED_ADDRESSES = 50_000;

	private final boolean enforce;
	private final Counter framesOverLimit;
	private final Counter connectsOverLimit;
	private final Map<String, Window> frames = new ConcurrentHashMap<>();
	private final Map<String, Window> connects = new ConcurrentHashMap<>();

	public SocketRateLimiter(boolean enforce, MeterRegistry meters) {
		this.enforce = enforce;
		this.framesOverLimit = Counter.builder("osparty.socket.ratelimit.frames")
			.description("Frames that exceeded the per-connection ceiling")
			.register(meters);
		this.connectsOverLimit = Counter.builder("osparty.socket.ratelimit.connects")
			.description("Connections that exceeded the per-address ceiling")
			.register(meters);
	}

	/** Whether a breach should be acted on, rather than only recorded. */
	public boolean enforcing() {
		return enforce;
	}

	/** Whether this frame is within the ceiling. Counts and logs either way. */
	public boolean allowFrame(String sessionId) {
		if (sessionId == null) {
			return true;
		}
		if (within(frames, sessionId, FRAME_WINDOW_MS, FRAMES_PER_WINDOW)) {
			return true;
		}
		framesOverLimit.increment();
		log.warn("Socket frame rate over ceiling: session={} limit={}/{}ms enforcing={}",
			sessionId, FRAMES_PER_WINDOW, FRAME_WINDOW_MS, enforce);
		return !enforce;
	}

	/** Whether this connection is within the per-address ceiling. Counts and logs either way. */
	public boolean allowConnect(String address) {
		if (address == null || connects.size() > MAX_TRACKED_ADDRESSES) {
			return true;
		}
		if (within(connects, address, CONNECT_WINDOW_MS, CONNECTS_PER_WINDOW)) {
			return true;
		}
		connectsOverLimit.increment();
		log.warn("Socket connect rate over ceiling: address={} limit={}/{}ms enforcing={}",
			address, CONNECTS_PER_WINDOW, CONNECT_WINDOW_MS, enforce);
		return !enforce;
	}

	/** Forget a connection's frame budget. Called on close, or the map grows with every session ever seen. */
	public void forget(String sessionId) {
		if (sessionId != null) {
			frames.remove(sessionId);
		}
	}

	private static boolean within(Map<String, Window> windows, String key, long windowMs, int limit) {
		long now = System.currentTimeMillis();
		Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
		synchronized (window) {
			if (now - window.startedAt.get() >= windowMs) {
				window.startedAt.set(now);
				window.count.set(0);
			}
			return window.count.incrementAndGet() <= limit;
		}
	}

	private static final class Window {
		final AtomicLong startedAt;
		final AtomicLong count = new AtomicLong();

		Window(long startedAt) {
			this.startedAt = new AtomicLong(startedAt);
		}
	}
}

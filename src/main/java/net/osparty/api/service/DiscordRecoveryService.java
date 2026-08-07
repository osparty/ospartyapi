package net.osparty.api.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Getting back onto an account by proving you own the Discord account it is linked to.
 *
 * <p><b>Why this exists.</b> Coupling needs a second machine with a screen; recovery codes need a sheet of
 * paper somebody kept. The failure this covers is the ordinary one -- the old PC died, nothing was written
 * down, and the only thing the user still has is the Discord account they linked months ago. Without it that
 * user's account hash stays claimed by a machine that no longer exists.
 *
 * <p><b>Why it is not circular.</b> Recovery is only offered against a link a signed-in session made
 * ({@code discord_link.verified}). Before that check existed, anyone could bind their own Discord account to
 * a hash they merely named, so "prove you own the linked Discord" would have proved only that the attacker
 * owned the Discord account they had planted there. See {@code 006-account-recovery.yaml}.
 *
 * <h2>Nonce, ticket, and why there are two</h2>
 * The browser tab and the socket are different conversations, on different pods, and neither can call the
 * other. So the flow is: the socket asks to start, gets a <em>ticket</em> back and holds it privately; the
 * browser carries a <em>nonce</em> through Discord and back to whichever pod answers the callback; that pod
 * writes an approval against the ticket; the socket polls for it.
 *
 * <p>They are two values because they have opposite exposure. The nonce travels in a URL -- through the
 * address bar, Discord's servers, and the browser's history -- so it cannot be the thing that grants the
 * credential. The ticket never leaves the socket, so it can be. Collapsing them would mean anyone who saw
 * the URL could claim the enrolment it was meant to authorise.
 *
 * <p>Keying the approval on the ticket rather than the account is what lets the plugin reconnect mid-flow
 * without losing it, and stops anyone else who knows the account hash from polling for an approval they did
 * not ask for.
 */
@Service
public class DiscordRecoveryService {
	private static final Logger log = LoggerFactory.getLogger(DiscordRecoveryService.class);

	/**
	 * Marks an OAuth {@code state} as belonging to a recovery rather than an ordinary link. Both come back
	 * to one callback -- the redirect URI is registered with Discord and there is only one of it -- so the
	 * state has to say which flow it is.
	 */
	public static final String STATE_PREFIX = "r.";

	private static final String NONCE_KEY = "auth:recover:nonce:";
	private static final String APPROVAL_KEY = "auth:recover:ok:";
	/** Long enough to find the right Discord login; short enough that an abandoned attempt stops mattering. */
	private static final Duration TTL = Duration.ofMinutes(10);
	private static final char SEPARATOR = '\0';
	private static final SecureRandom RANDOM = new SecureRandom();

	private final StringRedisTemplate redis;
	private final DiscordLinkService links;

	public DiscordRecoveryService(StringRedisTemplate redis, DiscordLinkService links) {
		this.redis = redis;
		this.links = links;
	}

	/** A started recovery: where to send the browser, and the secret the socket polls with. */
	public record Started(String url, String ticket) {
	}

	/** What a returning callback is for. */
	public record Pending(long accountHash, String ticket) {
	}

	/**
	 * Whether an account could be recovered this way at all, for deciding what to offer a client that has
	 * just failed to sign in.
	 *
	 * <p>Answering this to an unauthenticated caller does tell it something about an account it has only
	 * named: that the account exists, is linked, and has verified the link. That is a thin disclosure next
	 * to what the caller already learned by being refused enrolment, and the alternative -- offering the
	 * option blind and failing after the user has been through Discord's consent screen -- is worse.
	 */
	public boolean available(long accountHash) {
		return links.isEnabled() && links.hasVerifiedLink(accountHash);
	}

	/**
	 * Start a recovery for {@code accountHash}, or empty when it has no link that could settle one.
	 *
	 * <p>The caller has proved nothing at this point and is not meant to have: what it gets back is a URL to
	 * Discord and a ticket that stays worthless unless the right person authorises it there.
	 */
	public Optional<Started> begin(long accountHash) {
		if (!available(accountHash)) {
			return Optional.empty();
		}
		String nonce = randomToken();
		String ticket = randomToken();
		try {
			redis.opsForValue().set(NONCE_KEY + nonce, accountHash + String.valueOf(SEPARATOR) + ticket, TTL);
		}
		catch (RuntimeException e) {
			log.warn("Recovery nonce write failed, refusing to start: {}", e.toString());
			return Optional.empty();
		}
		log.info("Started Discord recovery for account {}", accountHash);
		return Optional.of(new Started(links.authorizeUrl(STATE_PREFIX + nonce), ticket));
	}

	/**
	 * Read and retire the state a returning callback carries.
	 *
	 * <p>Consumed rather than merely read, so a replayed callback URL cannot start a second enrolment on the
	 * strength of one authorisation.
	 */
	public Optional<Pending> consumeNonce(String state) {
		if (state == null || !state.startsWith(STATE_PREFIX)) {
			return Optional.empty();
		}
		String key = NONCE_KEY + state.substring(STATE_PREFIX.length());
		String stored;
		try {
			stored = redis.opsForValue().get(key);
			if (stored != null) {
				redis.delete(key);
			}
		}
		catch (RuntimeException e) {
			log.warn("Recovery nonce read failed: {}", e.toString());
			return Optional.empty();
		}
		if (stored == null) {
			return Optional.empty();
		}
		int split = stored.indexOf(SEPARATOR);
		if (split < 0) {
			return Optional.empty();
		}
		try {
			return Optional.of(new Pending(Long.parseLong(stored.substring(0, split)),
				stored.substring(split + 1)));
		}
		catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/**
	 * Record that the person at the browser proved they own the account's linked Discord. Nothing is enrolled
	 * here -- the socket holding the ticket does that when it next polls, which is what keeps the credential
	 * on the connection that asked for it.
	 */
	public void approve(String ticket, long accountHash) {
		try {
			redis.opsForValue().set(APPROVAL_KEY + ticket, Long.toString(accountHash), TTL);
		}
		catch (RuntimeException e) {
			log.warn("Recovery approval write failed for account {}: {}", accountHash, e.toString());
		}
	}

	/**
	 * Take the approval for a ticket, if one has landed. Single-use: one authorisation enrols one machine.
	 *
	 * @return the account the approval was for, or empty while none has arrived (which is what most polls
	 *     see, and is not an error).
	 */
	public Optional<Long> claim(String ticket) {
		if (ticket == null || ticket.isBlank()) {
			return Optional.empty();
		}
		String key = APPROVAL_KEY + ticket;
		try {
			String stored = redis.opsForValue().get(key);
			if (stored == null) {
				return Optional.empty();
			}
			redis.delete(key);
			return Optional.of(Long.parseLong(stored));
		}
		catch (RuntimeException e) {
			// Covers a Redis failure and a value that somehow is not a number; neither is worth telling
			// apart here, because both mean the same thing to the caller: no approval to claim.
			log.warn("Recovery approval read failed: {}", e.toString());
			return Optional.empty();
		}
	}

	private static String randomToken() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}

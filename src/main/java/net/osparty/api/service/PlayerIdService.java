package net.osparty.api.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The public name for an OSRS character: a short opaque string derived from its account hash, stable for the
 * life of that character and safe to show to everybody.
 *
 * <p><b>What it is for.</b> A player's display name changes; the person does not. Showing a player id beside
 * the name means a rename is visible as what it is -- same id, new name -- instead of looking like a
 * stranger. That is the whole feature, and it is why the id has to be stable and public.
 *
 * <p><b>Why the raw account hash cannot be that id.</b> It is the thing a client asserts to claim an
 * identity. Publishing it hands out the one input needed to impersonate someone, and it is exactly what made
 * trust-on-first-use enrolment unsafe: whoever enrols a hash first owns it, so a harvestable hash is a
 * squattable account. Deriving a separate id lets the public half be as public as we like while the account
 * hash never leaves the client that owns it.
 *
 * <p><b>Why salted.</b> An account hash is a 64-bit integer, and Jagex does not promise it is uniformly
 * distributed over that space. An unsalted digest would be a dictionary attack against whatever the real
 * distribution turns out to be -- derive ids for every plausible hash once, then reverse any id for free. A
 * server-held salt makes the mapping uncomputable off our machines however small the real input space is.
 *
 * <p><b>Why 60 bits.</b> Ids collide by the birthday bound, not by population: at a million characters, 40
 * bits collides about half the time and two unrelated players would share a public identity. Twelve
 * Crockford base32 characters is 60 bits, which holds the same population under a one-in-a-million collision
 * chance. Crockford because these get read aloud and typed back: no I, L, O or U, so nothing reads as
 * something else.
 */
@Service
public class PlayerIdService {
	private static final Logger log = LoggerFactory.getLogger(PlayerIdService.class);

	/** Crockford base32: digits then consonant-heavy letters, with I/L/O/U removed as look-alikes. */
	private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
	private static final int CHARS = 12;
	/** Grouped for reading: {@code ABCD-EFGH-JKMN}. */
	private static final int GROUP = 4;

	/**
	 * Used when nothing is configured. Dev and test only: it is in public source, so ids derived from it are
	 * computable by anyone and prove nothing. Production sets a real one and must never change it -- the salt
	 * is part of every id, so rotating it renames every player at once.
	 */
	private static final String DEV_SALT = "osparty-dev-salt-not-for-production";

	private final byte[] salt;

	public PlayerIdService(@Value("${app.auth.player-id-salt:}") String salt) {
		if (salt == null || salt.isBlank()) {
			log.error("app.auth.player-id-salt is not set: falling back to the public development salt. "
				+ "Player ids are derivable by anyone until this is configured.");
			this.salt = DEV_SALT.getBytes(StandardCharsets.UTF_8);
		}
		else {
			this.salt = salt.getBytes(StandardCharsets.UTF_8);
		}
	}

	/**
	 * The public id for {@code accountHash}, or null when there is no account (logged out, or a client that
	 * never reported one). Null rather than a placeholder: an id that several people share would defeat the
	 * one thing the id is for.
	 */
	public String of(long accountHash) {
		if (accountHash == 0 || accountHash == -1) {
			return null;
		}
		byte[] digest = sha256(accountHash);
		StringBuilder out = new StringBuilder(CHARS + CHARS / GROUP);
		for (int i = 0; i < CHARS; i++) {
			if (i > 0 && i % GROUP == 0) {
				out.append('-');
			}
			// Five bits per character, walked across the digest rather than taken from one byte each, so all
			// 60 bits come from the hash instead of 12 bytes contributing five bits apiece.
			int bit = i * 5;
			int value = ((digest[bit / 8] & 0xFF) << 8 | (digest[bit / 8 + 1] & 0xFF)) >> (11 - bit % 8);
			out.append(ALPHABET[value & 0x1F]);
		}
		return out.toString();
	}

	private byte[] sha256(long accountHash) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(salt);
			digest.update(ByteBuffer.allocate(Long.BYTES).putLong(accountHash).array());
			return digest.digest();
		}
		catch (NoSuchAlgorithmException e) {
			// Every JVM ships SHA-256; this cannot happen on a platform that could have started.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}

package net.osparty.api.web;

import net.osparty.api.service.DiscordLinkService;
import net.osparty.api.service.DiscordOAuthClient;
import net.osparty.api.service.DiscordRecoveryService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/discord/link")
public class DiscordLinkController {
	private static final Logger log = LoggerFactory.getLogger(DiscordLinkController.class);

	private final DiscordLinkService links;
	private final DiscordRecoveryService recovery;
	private final DiscordOAuthClient oauth;

	public DiscordLinkController(DiscordLinkService links, DiscordRecoveryService recovery,
		DiscordOAuthClient oauth) {
		this.links = links;
		this.recovery = recovery;
		this.oauth = oauth;
	}

	/**
	 * Where Discord sends the browser back to, for both flows.
	 *
	 * <p>One redirect URI is registered with Discord, so linking and recovery arrive here together and are
	 * told apart by the {@code state} they carry. Recovery is checked first because its states are the
	 * prefixed ones; anything else is an ordinary link.
	 */
	@GetMapping("/callback")
	public ResponseEntity<String> callback(
		@RequestParam(required = false) String code,
		@RequestParam(required = false) String state,
		@RequestParam(required = false) String error) {
		if (error != null) {
			return page("Cancelled", "You cancelled the Discord authorization. Nothing was changed.");
		}
		if (code == null || state == null) {
			return page("Failed", "Missing authorization details. Please start again from RuneLite.");
		}
		if (state.startsWith(DiscordRecoveryService.STATE_PREFIX)) {
			return recover(code, state);
		}
		Optional<Long> accountHash = links.consumeNonce(state);
		if (accountHash.isEmpty()) {
			return page("Link expired", "This link expired or was already used. Start again from RuneLite.");
		}
		Optional<DiscordLinkService.Link> user = oauth.exchangeForUser(code);
		if (user.isEmpty()) {
			return page("Link failed", "Couldn't verify your Discord account. Please try again.");
		}
		// Verified: the socket that started this had already proved it was this account -- handleStartDiscordLink
		// refuses otherwise -- which is what makes the resulting row usable for recovery later.
		links.link(accountHash.get(), user.get().discordId(), user.get().username(), true);
		log.info("Linked accountHash {} to Discord user {} ({})",
			accountHash.get(), user.get().discordId(), user.get().username());
		return page("Linked!", "Your Discord account <b>" + escape(user.get().username())
			+ "</b> is now linked to your OSRS account. You can close this tab and return to RuneLite.");
	}

	/**
	 * The recovery half: somebody with no credential is asking to be let back onto an account, and the only
	 * thing that can settle it is whether they control the Discord account that account was linked to.
	 *
	 * <p>Nothing is enrolled here. The approval is left for the socket holding the ticket to claim, so the
	 * credential goes to the connection that asked rather than to whoever happens to open this URL.
	 */
	private ResponseEntity<String> recover(String code, String state) {
		Optional<DiscordRecoveryService.Pending> pending = recovery.consumeNonce(state);
		if (pending.isEmpty()) {
			return page("Recovery expired",
				"This recovery link expired or was already used. Start again from RuneLite.");
		}
		Optional<DiscordLinkService.Link> user = oauth.exchangeForUser(code);
		if (user.isEmpty()) {
			return page("Recovery failed", "Couldn't verify your Discord account. Please try again.");
		}
		String linked = links.discordIdForAccountHash(pending.get().accountHash()).orElse(null);
		if (linked == null || !linked.equals(user.get().discordId())) {
			// Says which account was expected only in the log. Telling the browser would confirm to whoever
			// opened it which Discord account owns the OSRS account they were guessing at.
			log.warn("Discord recovery refused for account {}: signed in as {} but linked to {}",
				pending.get().accountHash(), user.get().discordId(), linked);
			return page("Not this account",
				"That Discord account isn't the one linked to this OSRS account. "
					+ "Sign in to Discord as the right account and try again.");
		}
		recovery.approve(pending.get().ticket(), pending.get().accountHash());
		log.info("Discord recovery approved for account {} by Discord user {}",
			pending.get().accountHash(), user.get().discordId());
		return page("Verified!", "You can close this tab and return to RuneLite — "
			+ "this device will be signed in within a few seconds.");
	}

	private static ResponseEntity<String> page(String title, String body) {
		String html = "<!doctype html><html lang=en><head><meta charset=utf-8>"
			+ "<meta name=viewport content='width=device-width,initial-scale=1'>"
			+ "<title>" + escape(title) + "</title></head>"
			+ "<body style='font-family:system-ui,sans-serif;background:#1a1a1a;color:#eee;margin:0'>"
			+ "<div style='max-width:480px;margin:15vh auto;padding:24px;text-align:center'>"
			+ "<h1 style='color:#5865F2'>" + escape(title) + "</h1>"
			+ "<p style='font-size:16px;line-height:1.5'>" + body + "</p></div></body></html>";
		return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
	}

	private static String escape(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}

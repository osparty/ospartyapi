package net.osparty.api.web;

import net.osparty.api.service.DiscordLinkService;
import net.osparty.api.service.DiscordOAuthClient;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth2 redirect target. Discord sends the user here after they authorize; we consume the {@code state}
 * nonce (which identifies the OSRS accountHash), exchange the {@code code} for the Discord user, persist
 * the binding, and render a small "you can return to RuneLite" page. The plugin learns of success by
 * polling {@code getDiscordLink} over the WebSocket — this endpoint only serves the browser.
 */
@RestController
@RequestMapping("/api/v1/discord/link")
public class DiscordLinkController {
	private static final Logger log = LoggerFactory.getLogger(DiscordLinkController.class);

	private final DiscordLinkService links;
	private final DiscordOAuthClient oauth;

	public DiscordLinkController(DiscordLinkService links, DiscordOAuthClient oauth) {
		this.links = links;
		this.oauth = oauth;
	}

	@GetMapping("/callback")
	public ResponseEntity<String> callback(
		@RequestParam(required = false) String code,
		@RequestParam(required = false) String state,
		@RequestParam(required = false) String error) {
		if (error != null) {
			return page("Link cancelled", "You cancelled the Discord authorization. Nothing was linked.");
		}
		if (code == null || state == null) {
			return page("Link failed", "Missing authorization details. Please start again from RuneLite.");
		}
		Optional<Long> accountHash = links.consumeNonce(state);
		if (accountHash.isEmpty()) {
			return page("Link expired", "This link expired or was already used. Start again from RuneLite.");
		}
		Optional<DiscordLinkService.Link> user = oauth.exchangeForUser(code);
		if (user.isEmpty()) {
			return page("Link failed", "Couldn't verify your Discord account. Please try again.");
		}
		links.link(accountHash.get(), user.get().discordId(), user.get().username());
		log.info("Linked accountHash {} to Discord user {} ({})",
			accountHash.get(), user.get().discordId(), user.get().username());
		return page("Linked!", "Your Discord account <b>" + escape(user.get().username())
			+ "</b> is now linked to your OSRS account. You can close this tab and return to RuneLite.");
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

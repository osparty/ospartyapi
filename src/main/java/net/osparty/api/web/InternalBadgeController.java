package net.osparty.api.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.osparty.api.service.DiscordBadgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/badges")
public class InternalBadgeController {
	private final DiscordBadgeService badges;

	public InternalBadgeController(DiscordBadgeService badges) {
		this.badges = badges;
	}

	@PostMapping
	public ResponseEntity<Void> set(@RequestBody BadgePush push) {
		if (push == null || push.discordId() == null || push.discordId().isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		badges.setBadges(push.discordId(), push.badges());
		return ResponseEntity.noContent().build();
	}

	@PutMapping
	public ResponseEntity<Void> replaceAll(@RequestBody List<BadgePush> pushes) {
		if (pushes == null) {
			return ResponseEntity.badRequest().build();
		}
		Map<String, List<String>> byDiscordId = new HashMap<>();
		for (BadgePush push : pushes) {
			if (push != null && push.discordId() != null && !push.discordId().isBlank()) {
				byDiscordId.put(push.discordId(), push.badges());
			}
		}
		badges.replaceAll(byDiscordId);
		return ResponseEntity.noContent().build();
	}

	public record BadgePush(String discordId, List<String> badges) {
	}
}

package net.osparty.api.web;

import net.osparty.api.PartyRepository;
import net.osparty.api.PartyRepository.Authorization;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The advertising contract the plugin talks to:
 *
 * <pre>
 *   GET    /api/v1/parties?activity={id}&amp;player={name}   -&gt; Party[]  (public only)
 *   GET    /api/v1/parties/by-code/{code}                  -&gt; Party   (public or private)
 *   POST   /api/v1/parties        (PartyRequest)           -&gt; Party (201)
 *   PUT    /api/v1/parties/{id}/heartbeat                  -&gt; Party   (host keep-alive)
 *   DELETE /api/v1/parties/{id}                            -&gt; Party
 * </pre>
 *
 * <p>The {@code /api/v1} prefix is applied by {@link WebConfig}; this controller
 * is mapped at {@code /parties}.
 *
 * Membership/roster is intentionally absent — that runs peer-to-peer in the
 * client, keyed by the {@code passphrase} carried on each ad.
 */
@RestController
@RequestMapping("/parties")
public class PartyController
{
	/**
	 * Header carrying the host's per-party secret. The plugin mints it on create; the
	 * server binds it to the party's session and requires it on host-only mutations
	 * (heartbeat/delete), so a hand-rolled REST client can't hijack someone's ad.
	 */
	static final String HOST_KEY_HEADER = "X-OSParty-Host-Key";

	private final PartyRepository store;

	public PartyController(PartyRepository store)
	{
		this.store = store;
	}

	/**
	 * List open parties. {@code activity} narrows to one activity id; {@code player}
	 * is accepted (the plugin sends the logged-in name) but not used for filtering —
	 * the plugin hides your own ad client-side.
	 */
	@GetMapping
	public List<Party> list(
		@RequestParam(required = false) String activity,
		@RequestParam(required = false) String player)
	{
		return store.list(activity);
	}

	/** Look up a single party (public or private) by its invite code. */
	@GetMapping("/by-code/{code}")
	public Party byCode(@PathVariable String code)
	{
		return store.findByInviteCode(code).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party with code " + code));
	}

	/** Look up the ad hosted by a player (used by the plugin to rejoin after a restart). */
	@GetMapping("/by-host/{host}")
	public Party byHost(@PathVariable String host)
	{
		return store.findByHost(host).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party for host " + host));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Party create(@RequestBody PartyRequest request,
		@RequestHeader(value = HOST_KEY_HEADER, required = false) String hostKey)
	{
		if (isBlank(request.activity()) || isBlank(request.host()))
		{
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activity and host are required");
		}
		return store.create(request, hostKey);
	}

	/**
	 * Host keep-alive. The plugin calls this periodically while it hosts so the ad
	 * isn't reaped as stale. PUT (not POST) so it isn't subject to the create
	 * rate limit. Requires the host key; 404 if the ad is gone, 403 if the key is wrong.
	 */
	@PutMapping("/{id}/heartbeat")
	public Party heartbeat(@PathVariable String id, @RequestParam(required = false) Integer size,
		@RequestParam(required = false) String world, @RequestParam(required = false) String layout,
		@RequestParam(required = false) String roles,
		@RequestHeader(value = HOST_KEY_HEADER, required = false) String hostKey)
	{
		requireHost(id, hostKey);
		return store.heartbeat(id, size, world, layout, roles).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party " + id));
	}

	@DeleteMapping("/{id}")
	public Party delete(@PathVariable String id,
		@RequestHeader(value = HOST_KEY_HEADER, required = false) String hostKey)
	{
		requireHost(id, hostKey);
		return store.delete(id).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party " + id));
	}

	/** Enforce host ownership for a mutation: 404 if no such ad, 403 if the key is wrong. */
	private void requireHost(String id, String hostKey)
	{
		Authorization auth = store.authorize(id, hostKey);
		if (auth == Authorization.NOT_FOUND)
		{
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No party " + id);
		}
		if (auth == Authorization.FORBIDDEN)
		{
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the host of party " + id);
		}
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.isBlank();
	}
}

package net.osparty.api.web;

import net.osparty.api.PartyRepository;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The advertising contract the plugin talks to:
 *
 * <pre>
 *   GET    /api/v1/parties?activity={id}&amp;player={name}   -&gt; Party[]
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

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Party create(@RequestBody PartyRequest request)
	{
		if (isBlank(request.activity()) || isBlank(request.host()))
		{
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activity and host are required");
		}
		return store.create(request);
	}

	/**
	 * Host keep-alive. The plugin calls this periodically while it hosts so the ad
	 * isn't reaped as stale. PUT (not POST) so it isn't subject to the create
	 * rate limit. 404 if the ad is already gone.
	 */
	@PutMapping("/{id}/heartbeat")
	public Party heartbeat(@PathVariable String id)
	{
		return store.heartbeat(id).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party " + id));
	}

	@DeleteMapping("/{id}")
	public Party delete(@PathVariable String id)
	{
		return store.delete(id).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party " + id));
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.isBlank();
	}
}

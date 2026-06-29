package net.osparty.api.web.rest;

import net.osparty.api.repository.PartyRepository;
import net.osparty.api.repository.PartyRepository.Authorization;
import net.osparty.api.model.Party;
import net.osparty.api.model.PartyRequest;
import net.osparty.api.model.PartyUpdate;
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

@RestController
@RequestMapping("/parties")
public class PartyController {
	static final String HOST_KEY_HEADER = "X-OSParty-Host-Key";

	private final PartyRepository store;

	public PartyController(PartyRepository store) {
		this.store = store;
	}

	@GetMapping
	public List<Party> list(@RequestParam(required = false) String activity) {
		return store.list(activity);
	}

	@GetMapping("/by-code/{code}")
	public Party byCode(@PathVariable String code) {
		return store.findByInviteCode(code).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party with code " + code));
	}

	@GetMapping("/by-host/{host}")
	public Party byHost(@PathVariable String host) {
		return store.findByHost(host).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party for host " + host));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Party create(@RequestBody PartyRequest request,
		@RequestHeader(value = HOST_KEY_HEADER, required = false) String hostKey) {
		if (isBlank(request.activity()) || isBlank(request.host())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activity and host are required");
		}
		return store.create(request, hostKey);
	}

	@PutMapping("/{id}")
	public Party update(@PathVariable String id,
		@RequestBody(required = false) PartyUpdate patch,
		@RequestHeader(value = HOST_KEY_HEADER, required = false) String hostKey) {
		requireHost(id, hostKey);
		return store.update(id, patch == null ? new PartyUpdate() : patch).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party " + id));
	}

	@PutMapping("/{id}/heartbeat")
	public Party heartbeat(@PathVariable String id, @RequestParam(required = false) Integer size,
		@RequestParam(required = false) String world, @RequestParam(required = false) String layout,
		@RequestParam(required = false) String roles,
		@RequestHeader(value = HOST_KEY_HEADER, required = false) String hostKey) {
		requireHost(id, hostKey);
		return store.heartbeat(id, size, world, layout, roles).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party " + id));
	}

	@DeleteMapping("/{id}")
	public Party delete(@PathVariable String id,
		@RequestHeader(value = HOST_KEY_HEADER, required = false) String hostKey) {
		requireHost(id, hostKey);
		return store.delete(id).orElseThrow(
			() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No party " + id));
	}

	private void requireHost(String id, String hostKey) {
		Authorization auth = store.authorize(id, hostKey);
		if (auth == Authorization.NOT_FOUND) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No party " + id);
		}
		if (auth == Authorization.FORBIDDEN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the host of party " + id);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}

# osrs-party-api

A small **Spring Boot** service that backs the [OSParty](../aio-party-plugin)
RuneLite plugin. It is purely an **advertising bulletin board**: it lists open
party ads and accepts new ones. It tracks **no membership** — the live roster
runs peer-to-peer inside the client, keyed by the `passphrase` carried on each
ad.

## Endpoints

All endpoints live under the versioned base path **`/api/v1`**.

| Method | Path                             | Body / Query                   | Returns                 |
|--------|----------------------------------|--------------------------------|-------------------------|
| GET    | `/api/v1/parties`                | `?activity={id}&player={name}` | `Party[]` (public only) |
| GET    | `/api/v1/parties/by-code/{code}` | —                              | `Party`                 |
| POST   | `/api/v1/parties`                | `PartyRequest` (+ host key)    | `Party` (201)           |
| PUT    | `/api/v1/parties/{id}/heartbeat` | host key                       | `Party`                 |
| DELETE | `/api/v1/parties/{id}`           | host key                       | `Party`                 |

`activity` filters to one activity id (e.g. `cox`, `tob`, `toa`, `nex`, …).
`player` is accepted but unused — the plugin hides your own ad client-side.

**Host authentication** — host-only mutations are gated by a per-party secret so a
hand-rolled REST client can't hijack or close someone else's ad. On `POST` the
plugin mints a random key and sends it in the **`X-OSParty-Host-Key`** header; the
server stores it in the party's session (never returned in any response) and
requires the same header on that party's `heartbeat` and `DELETE`:

- correct key → the mutation proceeds,
- wrong / missing key on a key-bearing ad → **`403 Forbidden`**,
- no such ad → **`404`**.

Ads created **without** a key stay open (no header required) — backward
compatibility for plugin versions that predate the feature; those ads simply
aren't protected until the host updates and re-advertises.

**Party types** — an ad can be **private** (`privateParty`: excluded from the
public list, reachable only via its server-generated **`inviteCode`** at
`GET …/by-code/{code}`, case-insensitive), tagged with a **loot rule**
(`lootRule`: `FFA` / `SPLIT` / `UNSPECIFIED`), and/or **ironman-only**
(`ironmanOnly`, with the host's `hostAccountType` for display). The API just
stores these; the plugin filters and enforces them.

## Behaviour

- **One ad per host** — `POST /api/v1/parties` replaces any existing ad from the same
  host (case/whitespace-insensitive), so re-advertising never piles up.
- **Liveness & eviction** — each ad has a server-side `lastSeen` timestamp. The
  host keeps it alive with `PUT /api/v1/parties/{id}/heartbeat`; the plugin pings every
  30s. A scheduled reaper drops ads not heard from within `app.ads.ttl-ms`
  (default 90s), so parties whose host crashed/closed disappear from search.
- **Rate limiting** — `POST /api/v1/parties` is throttled to **1 request / 5s per
  client IP** (`app.rate-limit.interval-ms`); exceeding it returns `429` with a
  `Retry-After` header. GET/PUT/DELETE are not limited (so heartbeats and
  browsing are unaffected).

Configurable in `application.yml`:

```yaml
app:
  rate-limit:
    interval-ms: 5000     # min gap between POSTs per IP (0 disables)
  ads:
    ttl-ms: 90000         # reap ads not heartbeated within this window
    evict-interval-ms: 30000
```

### `PartyRequest` (POST body)

```json
{
  "activity": "cox",
  "host": "Zezima",
  "description": "Normal CM, exp only",
  "capacity": 4,
  "world": "302",
  "minKillCount": 500,
  "minHardModeKillCount": 50,
  "passphrase": "wine-of-zamorak-…",
  "privateParty": false,
  "lootRule": "SPLIT",
  "ironmanOnly": false,
  "hostAccountType": "NORMAL"
}
```

`activity` and `host` are required (otherwise `400`). The party-type fields are
optional (`lootRule` defaults to `UNSPECIFIED`).

### `Party` (response)

```json
{
  "id": "1000",
  "activity": "cox",
  "host": "Zezima",
  "description": "Normal CM, exp only",
  "size": 1,
  "capacity": 4,
  "world": "302",
  "createdAt": 1750000000000,
  "passphrase": "wine-of-zamorak-…",
  "minKillCount": 500,
  "minHardModeKillCount": 50,
  "members": ["Zezima"],
  "privateParty": false,
  "inviteCode": "Y2Y3D9",
  "lootRule": "SPLIT",
  "ironmanOnly": false,
  "hostAccountType": "NORMAL"
}
```

`size`/`members` are advisory only; the real roster lives in the P2P room.
`inviteCode` is server-generated.

## Run

```sh
./gradlew bootRun
```

Serves on `http://localhost:8080` (matches the plugin's default `API base URL`).
The store starts **empty** — `GET /api/v1/parties` returns `[]` until a party is
advertised via `POST /api/v1/parties` (the plugin does this when you create a party).

```sh
# quick smoke test
curl localhost:8080/api/v1/parties
curl 'localhost:8080/api/v1/parties?activity=cox'
curl -X POST localhost:8080/api/v1/parties -H 'Content-Type: application/json' \
  -d '{"activity":"tob","host":"You","capacity":4,"passphrase":"abc-def"}'
```

## Storage

Two backends, chosen by `app.storage`:

- **`memory`** (default) — in-process map. Fast, zero-ops, but ads are lost on
  restart. Used by the tests.
- **`redis`** — persists across restarts and uses Redis' **native key expiry**
  as the liveness mechanism: each ad is written with a TTL, the host's heartbeat
  refreshes it (`EXPIRE`), and Redis evicts it automatically when the host goes
  quiet (so the scheduled evictor is a no-op). Needs a Redis server.

```sh
# run Redis (e.g. Docker) then start the API pointed at it
docker run --rm -p 6379:6379 redis:7-alpine
./gradlew bootRun --args='--app.storage=redis'
# connection: spring.data.redis.host/port (default localhost:6379)
```

Keys: `party:{id}` → JSON ad, `partyhost:{host}` → id (enforces one ad per
host), `party:seq` → id counter. The `spring-boot-starter-data-redis` dependency
is always present but only connects when `app.storage=redis` (lazy), so the
default mode and the tests run without a Redis server.

## Run the stack with Docker

`docker-compose.yml` runs two services: the **API** (built from `./Dockerfile`)
and **Redis**. The Dockerfile copies in a **pre-built jar**, so build it first:

```sh
./gradlew bootJar          # -> build/libs/app.jar
docker compose up --build  # builds the image, starts api + redis
```

- **API** on `http://localhost:8080`, wired to the `redis` service
  (`APP_STORAGE=redis`) via compose env — no code/config changes needed.
- **Redis** — ads persist in the `redis-data` volume (`--appendonly yes`), so
  they survive both API and Redis restarts.
- `docker compose down` stops it (add `-v` to also wipe the volume).

### TLS / reverse proxy

TLS termination is handled by a **separate Nginx Proxy Manager stack** (not part
of this compose). The API publishes port `8080` on the host, so point an NPM
Proxy Host at it:

- *Forward Hostname / IP*: the host's address — e.g. `127.0.0.1`, or
  `host.docker.internal` from inside the NPM container
- *Forward Port*: `8080`
- optionally enable **SSL → Request a new Let's Encrypt certificate**, then point
  the plugin's `API base URL` at `https://party.example.com`.

> The jar is Java 17 bytecode on a `eclipse-temurin:17-jre` base. Rebuild the
> jar and re-run `up --build` to deploy a new version.

## Test / build

```sh
./gradlew test     # MockMvc + unit tests (run in memory mode, no Redis needed)
./gradlew build    # full build (jar in build/libs)
```

## Deploy

CI deploys are **tag-driven** (`.github/workflows/deploy.yml`). Pushing a semver
tag builds that version, ships the jar to the server over SSH, and runs
`docker compose up -d --build` there:

```sh
git tag v1.2.3 && git push origin v1.2.3   # builds + deploys version 1.2.3
```

The build version comes from the tag (`v1.2.3` -> `1.2.3`, via
`-PappVersion`), each release jar is archived on the server under
`releases/app-<version>.jar` (last 5 kept for rollback), the built image is
tagged `osparty-api:<version>` and `:latest`, and a GitHub Release is cut for the
tag. You can also trigger a manual deploy from the Actions tab (**Run workflow**),
optionally passing a version label.

Required repository **secrets**: `API_SERVER_ADDRESS`, `SSH_USER`, `SSH_KEY`
(PEM private key, no passphrase). Optional: `SSH_PORT` (defaults to 22). The SSH
user must be able to run `docker` / `docker compose`, which needs Docker Compose
v2 on the server.

## Layout

```
src/main/java/net/osparty/api/
  OsrsPartyApiApplication.java   # Spring Boot entry point (@EnableScheduling)
  PartyRepository.java           # storage interface
  InMemoryPartyRepository.java   # default in-process store (one-per-host + lastSeen eviction)
  RedisPartyRepository.java      # redis store (native TTL; app.storage=redis)
  PartyFactory.java              # shared Party-building + host normalization
  StaleAdEvictor.java            # scheduled reaper (no-op for redis)
  model/Party.java               # ad as returned to the plugin
  model/PartyRequest.java        # create-ad payload
  web/PartyController.java       # GET/POST/PUT-heartbeat/DELETE /api/v1/parties
  web/WebConfig.java             # applies the /api/v1 base path prefix
  web/RateLimitFilter.java       # 1 POST / 5s per client IP -> 429
  web/RequestLoggingFilter.java  # per-request access log (method, IP, status, latency)
```

> Requires JDK 17+. Compiles to Java 17 bytecode; runs on newer JDKs too.

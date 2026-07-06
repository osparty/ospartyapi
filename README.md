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
    ttl-ms: 90000         # ad TTL; refreshed by the host's socket (or legacy heartbeat)
  ws:
    enabled: true              # live party-list push (see below); false disables it
    reconcile-interval-ms: 5000 # how often changes are diffed + pushed to clients
    touch-interval-ms: 5000    # how often a hosting socket refreshes its ad's TTL
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

## Live updates (WebSocket)

So clients don't have to poll `GET /api/v1/parties` on a timer, the open list is
also pushed over a WebSocket at **`/api/v1/ws/parties`** (`wss://` behind TLS).

```
client → {"type":"subscribe","activity":"cox"}   # activity optional; omit for all public ads
server → {"type":"snapshot","version":N,"parties":[ … ]}   # current ads, once, on subscribe
server → {"type":"created","version":N,"party":{ … }}      # then deltas as the set changes
server → {"type":"updated","version":N,"party":{ … }}
server → {"type":"removed","version":N,"id":"1000"}
```

Deltas are **idempotent** (upsert/remove by id) and a reconnect re-sends a full
snapshot, so a dropped frame self-heals — clients keep a map by id and re-render
on each frame. `version` is a loose ordering hint. A connection only gets this
firehose after it `subscribe`s (and can `unsubscribe`), so a host-only socket
doesn't receive it.

Hosts also **write** over the same socket — these mutate the same store as the
REST endpoints, so the change reaches searchers through the reconciler above:

```
client → {"type":"host","key":"<uuid>","request":{ …PartyRequest… }}
server → {"type":"hosted","party":{ … }}              # directed ack: server-assigned id/inviteCode
client → {"type":"update","id":"7","key":"<uuid>","patch":{ …PartyUpdate… }}   # partial change
client → {"type":"resume","id":"7","key":"<uuid>"}    # reclaim the ad after a reconnect
server → {"type":"gone","id":"7"}                     #   …if the grace window already lapsed
client → {"type":"unhost","id":"7","key":"<uuid>"}    # disband
server → {"type":"error","id":"7","detail":"…"}       # directed: a write was rejected
```

**The socket is the keep-alive.** While a hosting session is open the server
refreshes that ad's TTL every `app.ws.touch-interval-ms` — no periodic heartbeat.
A dropped socket leaves the ad in its remaining TTL (the *grace* window, ≈
`ttl-ms − touch-interval-ms`); the host reconnects and `resume`s by id+key to keep
the **same** ad, or it lapses and is reaped. Ownership is the session that
created/reclaimed the ad; the host key is the cross-reconnect credential. (Writes
also accept the key directly, so a REST-created ad can be adopted by the socket.)

**Why it scales** — a single `PartyReconciler` diffs the ad set every
`app.ws.reconcile-interval-ms` and pushes the changes to all subscribers, so
server work is **constant per interval regardless of client count** (vs. every
client re-fetching the whole list every few seconds). Since the reconciler reads
the shared store, multiple API instances each reconcile and push to their own
subscribers with no cross-instance bus — the shared Redis *is* the bus. A change
reaches clients within one interval; TTL-expired ads surface as `removed`.

The REST endpoints stay fully supported (the plugin falls back to them if the
socket can't connect — older server / WS blocked), so the contract is additive —
`POST`/`PUT /{id}`/`DELETE` and the socket are two ways to drive the same store.

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

**Redis only.** Ads persist across restarts and Redis' **native key expiry** is
the liveness mechanism: each ad is written with a TTL (`app.ads.ttl-ms`), the host
keeps it fresh (its open WebSocket, or the legacy REST heartbeat), and Redis evicts
it automatically when the host goes quiet.

```sh
# run Redis (e.g. Docker) then start the API pointed at it
docker run --rm -p 6379:6379 redis:7-alpine
./gradlew bootRun
# connection: spring.data.redis.host/port (default localhost:6379)
```

Keys: `party:{id}` → JSON ad, `partyhost:{host}` → id (enforces one ad per host),
`partycode:{code}` → id, `partykey:{id}` → host credential, `party:seq` → id
counter. The test suite runs against an in-memory fake (`@Profile("test")`), so
`./gradlew test` needs no Redis.

## Run the stack with Docker

`docker-compose.yml` runs two services: the **API** (built from `./Dockerfile`)
and **Redis**. The Dockerfile copies in a **pre-built jar**, so build it first:

```sh
./gradlew bootJar             # -> build/libs/app.jar  (ALWAYS run this first)
docker compose up --build -d  # builds the image, starts api + redis
```

> ⚠️ **`--build` alone does not recompile your source.** The Dockerfile only
> `COPY`s `build/libs/app.jar`, so `docker compose up --build` just re-copies
> whatever jar is already there. If you skip `./gradlew bootJar`, the container
> silently runs **stale code** — the image rebuilds, the app starts fine, but your
> latest changes aren't in it. Always run `bootJar` before `docker compose up --build`.

- **API** on `http://localhost:8080`, wired to the `redis` service via compose env
  (`SPRING_DATA_REDIS_HOST=redis`) — no code/config changes needed.
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
- **enable "Websockets Support"** on the Proxy Host (Details tab) so the
  `/api/v1/ws/parties` upgrade is forwarded — otherwise the live push falls back
  to REST polling. NPM keeps idle upgraded connections for `proxy_read_timeout`
  (default 60s); the plugin pings every 20s, so the default is fine.

> The jar is Java 17 bytecode on a `eclipse-temurin:17-jre` base. Rebuild the
> jar and re-run `up --build` to deploy a new version.

## Monitoring (production)

The production `docker-compose.yml` runs a **Prometheus + Grafana** stack alongside the API.
It's not in the test stack — monitoring is production-only.

What's collected:

- **JVM** — heap/non-heap, GC pause rate, live threads, CPU (Actuator + Micrometer, automatic).
- **Active socket connections** — the `osparty_ws_connections_active` gauge, read live off
  `PartyBroadcaster`'s subscriber map (see `MetricsConfig`).
- **Redis query time** — Lettuce per-command latency timers (`lettuce_command_completion_*`),
  tagged by command. Only populated when `app.storage=redis` (i.e. in the deployed stack).
- **Redis server load** — a `redis-exporter` sidecar: ops/sec, memory, connected clients,
  keyspace hit ratio.

How it's wired (all private by default):

- The app exposes `/actuator/prometheus` on a **separate management port `9090`** that
  docker-compose does **not** publish — only the `prometheus` container reaches it over the
  compose network. It is never served on the public `8080` port.
- **Grafana** is bound to `127.0.0.1:3000` on the server. Reach it over an SSH tunnel:

  ```sh
  ssh -L 3000:localhost:3000 <server>    # then open http://localhost:3000
  ```

  Login is `admin` / `GRAFANA_ADMIN_PASSWORD` (set it in the server's `.env`; see `.env.example`).
  The Prometheus datasource and an **OSParty API** dashboard are auto-provisioned on first boot.
  To expose Grafana publicly instead, point the Nginx Proxy Manager stack at `127.0.0.1:3000`.

The `monitoring/` directory (scrape config + Grafana provisioning) is shipped to the server by
the deploy workflow alongside the jar and compose file.

## Test / build

```sh
./gradlew test     # MockMvc + WebSocket tests (in-memory fake, no Redis needed)
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
  PartyRepository.java           # storage interface (create/list/update/delete/authorize)
  RedisPartyRepository.java      # the store: native-TTL ads (@Profile("!test"))
  PartyFactory.java              # shared Party-building, host normalization, patch apply
  model/Party.java               # ad as returned to the plugin
  model/PartyRequest.java        # create-ad payload
  model/PartyUpdate.java         # partial-update payload (PUT /{id} and WS update)
  web/PartyController.java       # GET/POST / PUT /{id} (+ /heartbeat alias) / DELETE
  web/WebConfig.java             # applies the /api/v1 base path prefix
  web/RateLimitFilter.java       # 1 POST / 5s per client IP -> 429
  web/RequestLoggingFilter.java  # per-request access log (method, IP, status, latency)
  web/WebSocketConfig.java       # registers the /api/v1/ws/parties handler
  web/PartyBroadcaster.java      # WS handler: snapshot, deltas, and host writes (host/update/resume/unhost)
  web/PartyReconciler.java       # scheduled diff of the ad set -> created/updated/removed
src/test/java/net/osparty/api/
  FakePartyRepository.java       # in-memory PartyRepository for tests (@Profile("test"))
```

> Requires JDK 17+. Compiles to Java 17 bytecode; runs on newer JDKs too.

# osrs-party-api

A small **Spring Boot** service that backs the
[OSParty](https://github.com/osparty/osparty) RuneLite plugin. It is an
**advertising bulletin board**: it lists open party ads and accepts new ones. It
tracks **no membership** — the live roster runs peer-to-peer inside the client,
keyed by the `passphrase` carried on each ad. On top of discovery it also brokers
some **Discord** conveniences for hosts and members (account linking, role
badges, and voice-channel provisioning delegated to a separate bot service).

Party discovery and hosting run **entirely over a WebSocket**. The only HTTP
endpoints are the Discord OAuth return page and an internal, token-guarded badge
feed used by the bot — there is no REST party CRUD.

## Transport at a glance

| Surface | Path | Used by |
|---------|------|---------|
| **WebSocket** (all party traffic) | `/api/v1/ws/parties` (`wss://` behind TLS) | the plugin |
| Discord OAuth return | `GET /api/v1/discord/link/callback` | the user's browser |
| Internal badge feed | `POST` / `PUT /internal/badges` | the `osparty-discord` bot |

`/internal/*` is gated by the **`X-Internal-Token`** header (constant-time
compared against `app.discord.internal-token`); a missing/wrong token is `401`.
The Discord callback renders a small HTML result page.

## WebSocket protocol

A client opens one connection to `/api/v1/ws/parties`. Every message is a JSON
object with a `type`. On connect the server immediately sends a `presence` frame;
the client then chooses to **read** (subscribe to the live list), **write** (host
an ad), or both over the same socket.

### Read — the live party list

```
client → {"type":"subscribe","activity":"cox"}   // activity optional; omit for all public ads
server → {"type":"snapshot","version":N,"parties":[ … ]}        // full list, once, on subscribe
server → {"type":"batch","version":N,"created":[…],"updated":[…],"removed":["1000"]}  // then diffs
```

- `subscribe` scopes the feed to one activity id (e.g. `cox`, `tob`, `toa`,
  `nex`, …); omit `activity` for every public ad. `unsubscribe` stops the feed
  (the socket stays up for hosting).
- After the initial `snapshot`, changes arrive as **`batch`** frames: `created`
  carries full `Party` objects, `updated` carries minimal `PartyDelta`s (only the
  changed fields, plus `id`/`activity`), `removed` carries ids. A reconnect
  re-sends a full snapshot, so a dropped frame self-heals; clients keep a map by
  id and merge each frame. `version` is a loose ordering hint.

Private ads are never in the list; look them up directly:

```
client → {"type":"getByCode","code":"Y2Y3D9"}          server → {"type":"byCode","id":"Y2Y3D9","party":{…}|null}
client → {"type":"getByHost","host":"Zezima"}           server → {"type":"byHost","id":"Zezima","party":{…}|null}
```

### Write — hosting an ad

```
client → {"type":"host","key":"<uuid>","request":{ …PartyRequest… }}
server → {"type":"hosted","party":{ … }}              // directed ack: server-assigned id + inviteCode
client → {"type":"update","id":"7","key":"<uuid>","patch":{ …PartyUpdate… }}   // partial change
client → {"type":"resume","id":"7","key":"<uuid>"}    // reclaim the ad after a reconnect
server → {"type":"gone","id":"7"}                     //   …if the grace window already lapsed
client → {"type":"unhost","id":"7","key":"<uuid>"}    // disband (also tears down any voice channel)
client → {"type":"transferHost","id":"7","key":"<uuid>","host":"NewHost","newKey":"<uuid2>"}
server → {"type":"transferred","id":"7"}              // ad handed over, re-keyed to newKey
server → {"type":"error","id":"7","detail":"…"}       // directed: a write was rejected
```

Host writes mutate the same store the reconciler diffs, so a change reaches
searchers within one reconcile interval.

**Host authentication.** Host-only writes are gated by a per-party secret so a
hand-rolled client can't hijack or close someone else's ad. On `host` the plugin
mints a random `key`; the server stores it (in `partykey:{id}`, never returned in
any frame). A write is authorised if **either** it comes from the session that
created/reclaimed the ad (the bound *owner session*) **or** it carries the correct
`key`. Otherwise: `error` `"forbidden"`; on a missing ad, `error` `"gone"`.
`transferHost` re-keys the credential to `newKey` so the old host's key stops
working, and unbinds the old session — the new host adopts the ad by `resume`ing
with `newKey`.

**The socket is the keep-alive.** While a hosting session is open the server
refreshes that ad's TTL every `app.ws.touch-interval-ms`; there is no periodic
heartbeat frame. A dropped socket leaves the ad in its remaining TTL (the *grace*
window, ≈ `ttl-ms − touch-interval-ms`); the host reconnects and `resume`s by
id+key to keep the **same** ad, or it lapses and Redis evicts it. (Writes accept
the key directly, so an ad can be adopted by a fresh session.)

### Presence

```
server → {"type":"presence","version":N,"online":123}   // on connect, then whenever the count changes
```

`online` is the total connected plugin clients **across all API instances**: each
node writes its local count to Redis with a short TTL and the broadcast sums the
live nodes, so the number is cluster-wide, not per-instance.

### Discord (over the same socket)

```
client → {"type":"startDiscordLink","accountHash":123}     server → {"type":"discordLinkUrl","url":"https://discord.com/oauth2/authorize?…"}
client → {"type":"getDiscordLink","accountHash":123}        server → {"type":"discordLink","accountHash":123,"id":"<discordId>|null","username":"…","badgesVisible":true}
client → {"type":"unlinkDiscord","accountHash":123}         server → {"type":"discordLink", … id:null … }
client → {"type":"setBadgeVisibility","accountHash":123,"visible":false}   server → {"type":"discordLink", … "badgesVisible":false}
client → {"type":"createVoiceChannel","id":"7","key":"<uuid>"}             server → {"type":"voiceChannel","id":"7","url":"https://discord.gg/…"}
client → {"type":"requestVoiceAccess","id":"7","accountHash":123}          server → {"type":"voiceAccess","id":"7"}
client → {"type":"kickVoiceMember","id":"7","key":"<uuid>","accountHash":123}   // fire-and-forget
```

- **Account linking** hands back a Discord OAuth2 authorize URL to open in a
  browser; the user approves, Discord redirects to
  `/api/v1/discord/link/callback`, and the server binds `accountHash ↔ Discord
  user`. Only offered when `app.discord.oauth.client-id`/`redirect-uri` are set.
- **Voice channels** are provisioned by the separate **`osparty-discord`**
  service over HTTP (`app.discord.service-url`); the API never holds a gateway
  connection. `createVoiceChannel` is idempotent (the channel is stored on the
  ad). `requestVoiceAccess` is member self-service, verified by roster membership
  + an active Discord link. When `service-url` is unset a no-op stands in
  (dev/tests) and voice requests return `error` `"voice unavailable"`.
- **Role badges** are pushed to the API by the bot via `/internal/badges` and
  attached to party members (by `accountHash → Discord id`) when a snapshot/batch
  is built. A user can hide their own badges with `setBadgeVisibility`.

## Why it scales

A single `PartyReconciler` diffs the ad set every `app.ws.reconcile-interval-ms`
and pushes one `batch` per distinct activity scope to all subscribers, so server
work is **constant per interval regardless of client count** (vs. every client
re-fetching the whole list). Since the reconciler reads the shared store,
multiple API instances each reconcile and push to their own subscribers with no
cross-instance bus — **the shared Redis is the bus**. A change reaches clients
within one interval; TTL-expired ads surface as `removed`.

## Data shapes

### `PartyRequest` (the `host` frame's `request`)

```json
{
  "activity": "cox",
  "host": "Zezima",
  "hostAccountHash": 123456789,
  "description": "Normal CM, exp only",
  "capacity": 4,
  "world": "302",
  "minKillCount": 500,
  "minHardModeKillCount": 50,
  "passphrase": "wine-of-zamorak-…",
  "privateParty": false,
  "lootRule": "SPLIT",
  "ironmanOnly": false,
  "hostAccountType": "NORMAL",
  "hardMode": false,
  "invocation": 300,
  "requiredRoles": ["MAGE", "RANGE"],
  "hostRole": "MAGE",
  "learner": false,
  "teacher": false
}
```

`activity` and `host` are required. `lootRule` is `FFA` / `SPLIT` /
`UNSPECIFIED` (default). The API stores these; the plugin filters and enforces
them.

### `PartyUpdate` (the `update` frame's `patch`)

A partial diff — only the fields present are changed (nullable boxed types
distinguish "absent" from `0`/`false`): `size`, `members`, `world`, `layout`,
`neededRoles`, `description`, `capacity`, `lootRule`, `ironmanOnly`,
`privateParty`, `minKillCount`, `minHardModeKillCount`, `invocation`, `hardMode`,
`requiredRoles`, `hostRole`, `learner`, `teacher`. An `update` with an empty
patch is a pure TTL touch.

### `Party` (in `snapshot` / `hosted` / `batch.created`)

```json
{
  "id": "1000",
  "activity": "cox",
  "host": "Zezima",
  "description": "Normal CM, exp only",
  "size": 1,
  "capacity": 4,
  "world": "302",
  "layout": "…",
  "hardMode": false,
  "invocation": 300,
  "createdAt": 1750000000000,
  "passphrase": "wine-of-zamorak-…",
  "minKillCount": 500,
  "minHardModeKillCount": 50,
  "members": [{ "name": "Zezima", "accountHash": 123456789, "badges": ["…"] }],
  "privateParty": false,
  "inviteCode": "Y2Y3D9",
  "lootRule": "SPLIT",
  "ironmanOnly": false,
  "hostAccountType": "NORMAL",
  "requiredRoles": ["MAGE", "RANGE"],
  "hostRole": "MAGE",
  "neededRoles": ["RANGE"],
  "learner": false,
  "teacher": false,
  "discordChannelId": "…",
  "discordInviteUrl": "https://discord.gg/…"
}
```

`size`/`members` are advisory (the real roster lives in the P2P room);
`inviteCode` is server-generated. A `Member` deserialises from either a bare
name string or a `{name, accountHash, badges}` object, so old clients keep
working.

## Configuration (`application.yml`)

```yaml
app:
  ads:
    ttl-ms: 90000              # ad TTL; refreshed by the host's open socket, else reaped
  ws:
    enabled: true              # WebSocket transport; false leaves clients with no discovery/hosting
    reconcile-interval-ms: 5000 # how often changes are diffed + pushed to subscribers
    touch-interval-ms: 5000    # how often a hosting socket refreshes its ad's TTL
  discord:
    service-url: ${DISCORD_SERVICE_URL:}      # osparty-discord base URL; unset = voice disabled (no-op)
    internal-token: ${DISCORD_INTERNAL_TOKEN:} # shared secret guarding /internal/* (both directions)
    oauth:
      client-id: ${DISCORD_CLIENT_ID:}        # linking offered only when client-id + redirect-uri set
      client-secret: ${DISCORD_CLIENT_SECRET:}
      redirect-uri: ${DISCORD_REDIRECT_URI:}  # must match an OAuth2 redirect in the Developer Portal
```

The main API serves on `8080`; Actuator (`/actuator/health`,
`/actuator/prometheus`) runs on a **separate management port `9090`** that is
never part of the public Service.

## Run

```sh
./gradlew bootRun
```

Serves on `http://localhost:8080` (matches the plugin's default `API base URL`);
the WebSocket lives at `ws://localhost:8080/api/v1/ws/parties`. The store starts
**empty** until a party is advertised over the socket.

```sh
# smoke test: management health, then drive the socket with a WS client (e.g. websocat)
curl localhost:9090/actuator/health
echo '{"type":"subscribe"}' | websocat -n1 ws://localhost:8080/api/v1/ws/parties
```

## Storage

**Redis only.** Ads persist across restarts and Redis' **native key expiry** is
the liveness mechanism: each ad is written with a TTL (`app.ads.ttl-ms`), the
host's open socket keeps it fresh, and Redis evicts it automatically when the
host goes quiet.

```sh
docker run --rm -p 6379:6379 redis:7-alpine   # then bootRun points at it
./gradlew bootRun
# connection: spring.data.redis.host/port (default localhost:6379)
```

Keys:

- `party:{id}` → JSON ad · `party:ids` → the id index set · `party:seq` → id counter
- `partyhost:{host}` → id (enforces one ad per host) · `partycode:{code}` → id · `partykey:{id}` → host credential
- `discordlink:hash:{accountHash}` ↔ `discordlink:discord:{discordId}` → account link · `discordlink:nonce:{nonce}` → in-flight OAuth state
- `discordlink:badges:{discordId}` → role badges · `discordlink:badgeshidden:{accountHash}` → badge-privacy flag

The test suite runs against an in-memory fake (`@Profile("test")`), so
`./gradlew test` needs no Redis.

## Run the stack with Docker

`docker-compose.yml` runs the **API** (built from `./Dockerfile`), **Redis**, and
a local **Prometheus + Grafana** pair. The Dockerfile copies in a **pre-built
jar**, so build it first:

```sh
./gradlew bootJar             # -> build/libs/app.jar  (ALWAYS run this first)
docker compose up --build -d  # builds the image, starts the stack
```

> ⚠️ **`--build` alone does not recompile your source.** The Dockerfile only
> `COPY`s `build/libs/app.jar`, so `docker compose up --build` just re-copies
> whatever jar is already there. Skip `./gradlew bootJar` and the container
> silently runs **stale code**. Always run `bootJar` first.

- **API** on `http://localhost:8080`, wired to the `redis` service via compose env
  (`SPRING_DATA_REDIS_HOST=redis`).
- **Redis**: ads persist in the `redis-data` volume (`--appendonly yes`).
- `docker compose down` stops it (add `-v` to also wipe volumes).

### TLS / reverse proxy (self-hosting)

Production runs behind the cluster's Traefik ingress (see **Deploy**); for a
self-hosted compose setup, terminate TLS with any reverse proxy (e.g. Nginx Proxy
Manager) pointed at host port `8080`. Two things matter for the live push:

- **Enable WebSocket support** on the proxy so the `/api/v1/ws/parties` upgrade is
  forwarded; otherwise clients can't get the live list at all.
- Keep the idle read timeout comfortably above the client's ping (the plugin pings
  every 20s), so upgraded connections aren't cut. Then point the plugin's `API
  base URL` at `https://party.example.com`.

> The jar is Java 17 bytecode on an `eclipse-temurin:17-jre` base. Rebuild the jar
> and re-run `up --build` to deploy a new version.

## Monitoring (production)

Production monitoring runs in the k3s cluster: **kube-prometheus-stack** (Helm
values in `k8s/cluster/`), the app's ServiceMonitors and the **OSParty API**
Grafana dashboard from `k8s/base/`, and Grafana at
`https://monitoring.osparty.net` through the same Traefik ingress + cert-manager
as the API. Pod logs are queryable in **Loki** (Grafana → Explore); alert rules
notify the OSParty Discord.

What's collected:

- **JVM / Tomcat**: heap, GC, threads, CPU (Actuator + Micrometer).
- **Active socket connections**: the `osparty_ws_connections_active` gauge, read
  live off `PartyBroadcaster`'s subscriber map (see `MetricsConfig`).
- **Redis query time**: Lettuce per-command latency timers, tagged by command.
- **Redis server load**: a `redis-exporter` sidecar (ops/sec, memory, clients,
  keyspace hit ratio).

`/actuator/prometheus` is exposed only on management port **`9090`**, never on the
public Service; only Prometheus reaches it inside the cluster.

## Test / build

```sh
./gradlew test     # MockMvc + WebSocket tests (in-memory fake, no Redis needed)
./gradlew build    # full build (jar in build/libs/app.jar)
```

## Deploy (production, k3s)

Production is a 3-server Hetzner **k3s** cluster on a private network
(`10.0.0.0/24`). Everything under `k8s/` is the cluster's source of truth:

```
k8s/
  cluster/        one-time, cluster-scoped: Helm values (Traefik, kube-prometheus-stack, Loki,
                  Alloy), cert-manager issuers
  base/           the app: api Deployment/Service, redis StatefulSet, Ingress, ServiceMonitors,
                  Grafana dashboard
  overlays/prod/  namespace osparty: api.osparty.net, 3 api replicas
  sysctl/         host-global sysctls (conntrack, backlogs, file-max); copy to /etc/sysctl.d/
                  on every node, they cannot be set from inside the cluster
```

| Server | Private IP | Role |
|--------|-----------|------|
| api01  | 10.0.0.2  | control plane; Traefik ingress (80/443); redis + discord-bot pods pin here |
| api02  | 10.0.0.3  | agent |
| api03  | 10.0.0.4  | agent |

`.github/workflows/deploy-k8s.yml` (**Deploy API (Kubernetes)** in the Actions
tab) is the only deploy pipeline. A run builds the image, pushes it to
`ghcr.io/osparty/osparty-api`, applies the rendered `k8s/` manifests over one SSH
to the control plane, and only after the rollout succeeds tags the commit and
cuts a GitHub release.

Versioning is automatic semver: each run bumps the patch of the latest `v*.*.*`
tag. For a minor/major bump (or to redeploy an old version), run the workflow
manually with an explicit version. **Rollback**: `kubectl -n osparty rollout undo
deployment/osparty-api`, or a manual run with the previous version.

Required repository **secrets**: `API_SERVER_ADDRESS` (control-plane host),
`SSH_USER`, `SSH_KEY` (PEM private key, no passphrase); optional `SSH_PORT`
(default 22). The image push uses the workflow's `GITHUB_TOKEN`; the cluster
pulls via its `ghcr-pull` secret.

Runtime configuration lives in namespace secrets, created once by hand:

- **`osparty-api-env`** (namespace `osparty`): `SPRING_DATA_REDIS_PASSWORD`,
  `DISCORD_INTERNAL_TOKEN` (shared secret with the bot), `DISCORD_CLIENT_ID` /
  `DISCORD_CLIENT_SECRET` / `DISCORD_REDIRECT_URI` (OAuth linking),
  `DISCORD_SERVICE_URL` (the bot's in-cluster URL).
- **`ghcr-pull`** (namespace `osparty`): pull credentials for the internal GHCR packages.
- **`grafana-env`** (namespace `monitoring`): `DISCORD_ALERT_WEBHOOK_URL`.

The Discord bot deploys from its own repo (`osparty-discord`) into the same
namespace; the api pods reach it at `http://osparty-discord:8090`.

## Layout

```
src/main/java/net/osparty/api/
  OsrsPartyApiApplication.java        Spring Boot entry point (@EnableScheduling)
  model/                              Party, PartyRequest, PartyUpdate, PartyDelta, Member
  repository/
    PartyRepository.java              storage interface (+ Authorization)
    RedisPartyRepository.java         the store: native-TTL ads (@Profile("!test"))
  service/
    PartyFactory.java                 Party building, host normalization, invite codes, patch apply
    DiscordLinkService.java           OAuth2 account linking (accountHash <-> Discord)
    DiscordOAuthClient.java           Discord code/token/user exchange
    DiscordBadgeService.java          role badges: store + enrich party members at broadcast time
    VoiceChannelService.java          interface; provisioning delegated over HTTP
    HttpVoiceChannelService.java      calls the osparty-discord service
    DisabledVoiceChannelService.java  no-op when app.discord.service-url is unset
    VoiceChannelServiceConfig.java    picks Http vs Disabled
  web/
    DiscordLinkController.java        GET /api/v1/discord/link/callback (OAuth return page)
    InternalBadgeController.java      POST/PUT /internal/badges (bot -> server)
    filter/InternalTokenFilter.java   X-Internal-Token gate for /internal/*
    filter/RequestLoggingFilter.java  per-request access log (method, IP, status, latency)
    config/WebSocketConfig.java       registers /api/v1/ws/parties
    config/MetricsConfig.java         osparty_ws_connections_active gauge
    ws/
      PartyBroadcaster.java           WS handler: subscribe/snapshot/batch + host writes + discord/voice
      PartyReconciler.java            scheduled diff of the ad set -> created/updated/removed batch
      PresenceRegistry.java           cluster-wide online count (Redis / Local impls)
src/test/java/net/osparty/api/        MockMvc + WebSocket tests (FakePartyRepository, @Profile("test"))
```

> Requires JDK 17+. Compiles to Java 17 bytecode; runs on newer JDKs too.

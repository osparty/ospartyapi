# OSParty 3-server deployment

Horizontal scaling across three Hetzner servers on a private network. The API tier scales for
throughput; Redis + the Discord bot are singletons on MAIN (this scales capacity, not availability).

```
                     Internet (wss/https 443)
                             │
                      ┌──────▼──────┐  NPM (ingress + TLS) — on MAIN
                      │  osparty_api │  api.osparty.net · monitoring.osparty.net
                      │   upstream   │
             ┌────────┴───┬──────────┴────────┐  private net 10.0.0.0/24
        10.0.0.2:8080  10.0.0.3:8080     10.0.0.4:8080
      ┌──────▼──────┐ ┌────▼─────┐      ┌─────▼────┐
      │ MAIN 10.0.0.2│ │NODE-1    │      │NODE-2    │
      │ api #1       │ │10.0.0.3  │      │10.0.0.4  │
      │ redis :6379 ◄┼─┤ api      │      │ api      │
      │ discord-bot ◄┼─┤ (api-only)│     │(api-only)│
      │  :8090       │ └──────────┘      └──────────┘
      │ prometheus   │   ▲ scrapes :9090 on each node over the private net
      │ grafana · NPM│
      └──────────────┘
```

| Host   | Private IP | Runs |
|--------|-----------|------|
| MAIN   | 10.0.0.2  | NPM, redis, discord-bot, prometheus, grafana, redis-exporter, **api #1** (`docker-compose.main.yml`) |
| NODE-1 | 10.0.0.3  | api only (`docker-compose.node.yml`) |
| NODE-2 | 10.0.0.4  | api only (`docker-compose.node.yml`) |

## GitHub secrets (org-level, both repos)

Already set from the single-server phase: `API_SERVER_ADDRESS` (→ MAIN), `SSH_USER`, `SSH_KEY`,
`SSH_PORT` (opt), `GHCR_USERNAME`, `GHCR_TOKEN`.

**Add for the fleet:** `NODE_A_ADDRESS` (node-1 public/SSH addr) and `NODE_B_ADDRESS` (node-2). The same
`SSH_USER`/`SSH_KEY` must work on all three hosts.

## One-time infrastructure

1. **Private network** — attach all three servers to one Hetzner Cloud Network (e.g. `10.0.0.0/24`) with
   the IPs above.
2. **Hetzner Cloud Firewall**
   - MAIN inbound (public): `443` (NPM), `22` (SSH, restrict to your IP). Nothing else public.
   - Nodes inbound (public): `22` only.
   - Private subnet (`10.0.0.0/24`): allow between the three — `6379` (Redis), `8090` (bot), `8080` (API,
     from NPM), `9090` (API metrics, to Prometheus). All API/redis/bot ports bind to private IPs, so the
     firewall is defence-in-depth.
3. **Server dirs + `.env` files** (under the SSH user's home):
   - MAIN `~/osparty-api/.env` ← from `.env.main.example` (`MAIN_PRIVATE_IP=10.0.0.2`, `REDIS_PASSWORD`,
     `DISCORD_INTERNAL_TOKEN`, OAuth, Grafana).
   - MAIN `~/osparty-discord/.env` ← bot token/guild/category, **same** `DISCORD_INTERNAL_TOKEN`, and
     `BOT_BIND_IP=10.0.0.2` so the bot binds the private IP.
   - NODE-1 `~/osparty-api/.env` ← `.env.node.example` with `NODE_PRIVATE_IP=10.0.0.3`.
   - NODE-2 `~/osparty-api/.env` ← `.env.node.example` with `NODE_PRIVATE_IP=10.0.0.4`.
   - `REDIS_PASSWORD` and `DISCORD_INTERNAL_TOKEN` must be **identical** across MAIN + both nodes (and the
     bot's token matches the API's on MAIN).
4. **NPM** — mount custom config and load-balance:
   - Add `- ./npm-custom:/data/nginx/custom:ro` to the NPM stack's compose; put `deploy/npm/http_top.conf`
     at `./npm-custom/http_top.conf`.
   - On the `api.osparty.net` Proxy Host, paste `deploy/npm/api-proxy-host-advanced.conf` into the
     Advanced tab. `docker exec <npm> nginx -t` then reload.

## Deploy

Tag-driven, same as before — the workflow now builds once and rolls out to all three (one host at a time):

```sh
git tag v1.3.0 && git push origin v1.3.0
```

The bot deploys from its own repo (`osparty-discord`, MAIN only). Order for a first bring-up:
1. Redis reachable on `10.0.0.2:6379` (deploy MAIN first — `docker-compose.main.yml` brings up redis + api #1).
2. Deploy the bot (osparty-discord).
3. Bring up NODE-1 and NODE-2 (the same API tag rolls to them automatically).
4. Point NPM at the upstream; confirm `api.osparty.net` serves and WS upgrades.

**Rollback** (any host): `IMAGE_TAG=<old> docker compose -f docker-compose.<main|node>.yml up -d`.

## Verify

- `docker compose -f docker-compose.main.yml ps` on MAIN; `... node.yml ps` on each node — all `Up`.
- From a node: `redis-cli -h 10.0.0.2 -a "$REDIS_PASSWORD" ping` → `PONG`; `curl 10.0.0.2:8090/actuator/health`.
- Prometheus → Status → Targets: `osparty-api` shows 3 UP (labels `node=main|node-1|node-2`).
- Grafana: `sum(osparty_ws_connections_active)` = fleet-wide connections; the plugin's "active users" count
  is now a global total (Redis-aggregated) regardless of which node a client hit.

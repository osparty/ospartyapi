# Test deploy (`testing.osparty.net`)

An isolated copy of the API for testing, built from the **current source** and run
next to production on the same host. It has its **own Redis, volume, container
names, compose project, and published port (8081)**, so it never touches prod
(8080) or prod's ad data.

## How it deploys (GitHub Actions)

`.github/workflows/deploy-test.yml` runs automatically on **every push to `main`**
(and on demand via **Actions → Deploy API (test) → Run workflow**). Each run:

1. builds + tests the current source (`./gradlew clean test bootJar`),
2. stages the jar + `Dockerfile` + this `docker-compose.yml` into `~/osparty-api-test`
   on the server (via SSH/SCP),
3. runs `docker compose up -d --build` there.

It reuses the **same secrets** as the prod deploy (`API_SERVER_ADDRESS`,
`SSH_USER`, `SSH_KEY`, optional `SSH_PORT`) — nothing new to configure. Production
stays **tag-driven** (`deploy.yml`) and is untouched.

## Wiring up `testing.osparty.net` (one-time, NPM)

Same model as prod — add a **Proxy Host** in Nginx Proxy Manager:

- Domain: `testing.osparty.net`
- Forward to: `127.0.0.1` port `8081` (or `host.docker.internal:8081` from inside
  the NPM container), scheme `http`
- enable SSL / Force SSL as you do for prod

The published port keeps prod and test fully separate, so no shared docker network
is required.

### Alternative: route by container name over a shared network

If you'd rather NPM reach the container directly over a shared docker network,
attach the `api` service to your NPM network (the commented `networks:` block in
`docker-compose.yml`), point the proxy host at `http://osparty-api-test:8080`, and
drop the published `8081` mapping.

## Notes

- Config is identical to prod (`APP_STORAGE=redis`, same rate limit / TTL
  defaults) — it only differs in the stack/Redis it runs against.
- Quick check after a deploy: `curl https://testing.osparty.net/api/v1/parties` → `[]`.
- Manual fallback (rarely needed): on the server, `cd ~/osparty-api-test && docker
  compose up -d --build` after the bundle has been staged there by a CI run.

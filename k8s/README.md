# OSParty on k3s

Migration target for the 3-server compose fleet described in `deploy/README.md`. Same hardware, same
DNS — the cluster is built **alongside** the running Docker stacks and takes over at a deliberate
go-live. Redis data is ephemeral (ads TTL ≤ 90s), so there is no data migration: the go-live blip drops
current ads and host plugins re-create them on reconnect. Prod only — no test environment.

| Server | Private IP | Role |
|--------|-----------|------|
| api01  | 10.0.0.2  | k3s control plane; old MAIN (NPM, redis, bot compose stacks live here until go-live) |
| api02  | 10.0.0.3  | k3s agent; Traefik idles here until go-live (80/443 are NPM's on api01) |
| api03  | 10.0.0.4  | k3s agent |

```
k8s/
  cluster/        one-time, cluster-scoped: Helm values (traefik, kube-prometheus-stack), cert issuers
  base/           the app: api Deployment/Service, redis StatefulSet, Ingress, ServiceMonitors, dashboard
  overlays/prod/  namespace osparty — api.osparty.net, 3 replicas (deployed by deploy-k8s.yml)
```

What replaces what:

| Compose fleet | Cluster |
|---|---|
| NPM + generated `upstream osparty_api` + LE certs in the UI | Traefik (hostNetwork 80/443) + cert-manager |
| `api` container per host, SSH-matrix rolling deploy | `Deployment` ×3, native rolling update, `rollout undo` |
| Redis published on the private IP | `redis` StatefulSet pinned to api01, ClusterIP only |
| prometheus / grafana / node-exporter / cadvisor / redis-exporter containers | kube-prometheus-stack (+ exporter sidecar); cadvisor is the kubelet's |
| `.env` per host | namespace Secrets (below) |

k3s notes: single binary via systemd (no snap, no auto-updates — upgrades happen only when you re-run
the installer). CoreDNS, the `local-path` storage provisioner, and metrics-server are bundled. The
bundled **Traefik and ServiceLB are disabled** at install: the bundled Traefik exposes itself via
ServiceLB on **every** node's 80/443 (colliding with NPM on api01 pre-go-live), so we install Traefik
ourselves via the official Helm chart — hostNetwork, pinned to the ingress-labeled node. (Traefik rather
than ingress-nginx: the latter was retired/archived by the Kubernetes project in March 2026, no more
security patches.)

## Phase 0 — cluster bring-up (zero impact on the running fleet)

**Firewall** — nothing to change. k3s' node-to-node ports (`6443` API server, `8472/udp` flannel VXLAN,
`10250` kubelet) ride the private network, which Hetzner Cloud Firewalls don't filter. Do NOT open these
publicly: the public firewall stays default-deny (api01: 80/443/22, api02/api03: 22). The API server
binds all interfaces, so the public default-deny is what keeps 6443 unreachable — same defence-in-depth
posture as the old private-IP-bound Redis/API ports.

**0. Remove MicroK8s remnants — on ALL THREE servers** (skip on a box that never had it):

```sh
sudo snap remove microk8s --purge
```

**1. On api01** — install the k3s server, pinned to the private interface:

```sh
PRIV_IF=$(ip -o -4 addr show | awk '/10\.0\.0\./ {print $2}'); echo "private interface: $PRIV_IF"
curl -sfL https://get.k3s.io | INSTALL_K3S_CHANNEL=stable sh -s - server \
  --node-ip=10.0.0.2 \
  --flannel-iface="$PRIV_IF" \
  --disable=traefik,servicelb \
  --kubelet-arg=allowed-unsafe-sysctls=net.core.somaxconn

# Print the join token for the agents (used in steps 2 and 3):
sudo cat /var/lib/rancher/k3s/server/node-token
```

`--kubelet-arg=allowed-unsafe-sysctls=...` is required on every node: the api pods set
`net.core.somaxconn` and are rejected with `SysctlForbidden` on a node whose kubelet doesn't allow it.

**2. On api02** — join as an agent (paste the token from step 1):

```sh
PRIV_IF=$(ip -o -4 addr show | awk '/10\.0\.0\./ {print $2}'); echo "private interface: $PRIV_IF"
curl -sfL https://get.k3s.io | INSTALL_K3S_CHANNEL=stable \
  K3S_URL=https://10.0.0.2:6443 K3S_TOKEN='<token from api01>' sh -s - agent \
  --node-ip=10.0.0.3 \
  --flannel-iface="$PRIV_IF" \
  --kubelet-arg=allowed-unsafe-sysctls=net.core.somaxconn
```

**3. On api03** — same, with its own IP:

```sh
PRIV_IF=$(ip -o -4 addr show | awk '/10\.0\.0\./ {print $2}'); echo "private interface: $PRIV_IF"
curl -sfL https://get.k3s.io | INSTALL_K3S_CHANNEL=stable \
  K3S_URL=https://10.0.0.2:6443 K3S_TOKEN='<token from api01>' sh -s - agent \
  --node-ip=10.0.0.4 \
  --flannel-iface="$PRIV_IF" \
  --kubelet-arg=allowed-unsafe-sysctls=net.core.somaxconn
```

**4. On api01** — verify the cluster, then label nodes (placement is label-driven):

```sh
kubectl get nodes -o wide
# expect: api01/api02/api03 all Ready, INTERNAL-IP = 10.0.0.2 / 10.0.0.3 / 10.0.0.4

kubectl label node api01 osparty.net/role=main
kubectl label node api02 osparty.net/ingress=true   # pre-go-live: ingress idles on api02 (80/443 busy on api01)
```

**5. Host sysctls — on ALL THREE servers** (`deploy/sysctl/99-osparty.conf` still applies: conntrack,
file-max, backlogs):

```sh
sudo cp ~/osparty-api/deploy/sysctl/99-osparty.conf /etc/sysctl.d/99-osparty.conf 2>/dev/null || true
sudo sysctl --system
```

**6. On api01** — Helm + charts (this repo's `k8s/` dir is assumed at `~/osparty-api/k8s`; ship it with
`scp -r k8s root@10.0.0.2:~/osparty-api/` if it isn't):

```sh
curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
echo 'export KUBECONFIG=/etc/rancher/k3s/k3s.yaml' >> ~/.bashrc && export KUBECONFIG=/etc/rancher/k3s/k3s.yaml

# cert-manager (Helm chart; replaces NPM's Let's Encrypt UI flow)
helm repo add jetstack https://charts.jetstack.io
helm upgrade --install cert-manager jetstack/cert-manager \
  -n cert-manager --create-namespace --set crds.enabled=true
kubectl -n cert-manager wait --for=condition=Available deployment --all --timeout=180s
kubectl apply -f ~/osparty-api/k8s/cluster/cluster-issuers.yaml

# Traefik (hostNetwork 80/443 on the osparty.net/ingress=true node)
# If ingress-nginx from an earlier iteration is installed, remove it first:
#   helm uninstall ingress-nginx -n ingress-nginx
helm repo add traefik https://traefik.github.io/charts
helm upgrade --install traefik traefik/traefik \
  -n traefik --create-namespace -f ~/osparty-api/k8s/cluster/traefik-values.yaml
kubectl -n traefik rollout status deployment/traefik --timeout=180s

# kube-prometheus-stack (all heavy parts pinned to api01)
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
kubectl create namespace monitoring
kubectl -n monitoring create secret generic grafana-admin \
  --from-literal=admin-user=admin --from-literal=admin-password='<GRAFANA_ADMIN_PASSWORD>'
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  -n monitoring -f ~/osparty-api/k8s/cluster/kube-prometheus-stack-values.yaml
```

**7. On api01** — app secrets. Values come from the old `.env` on api01; nothing is committed:

```sh
kubectl create namespace osparty
kubectl -n osparty create secret generic osparty-api-env \
  --from-literal=SPRING_DATA_REDIS_PASSWORD='<REDIS_PASSWORD>' \
  --from-literal=DISCORD_INTERNAL_TOKEN='<DISCORD_INTERNAL_TOKEN>' \
  --from-literal=DISCORD_CLIENT_ID='<...>' \
  --from-literal=DISCORD_CLIENT_SECRET='<...>' \
  --from-literal=DISCORD_REDIRECT_URI='https://api.osparty.net/api/v1/discord/link/callback'
# GHCR pull credentials (the package is internal) — reuses the existing GHCR_USERNAME/GHCR_TOKEN PAT:
kubectl -n osparty create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io --docker-username='<GHCR_USERNAME>' --docker-password='<GHCR_TOKEN>'
```

Sanity check: `kubectl get pods -A | grep -vE 'Running|Completed'` should come back (near-)empty, and
`kubectl top nodes` should show the 4GB nodes with headroom next to the still-running compose containers.
Watch for node-exporter crash-looping on api02/api03: if its logs say `bind: address already in use`,
the old compose node-exporter still holds port 9100 there — stop it
(`docker compose -f docker-compose.node.yml stop node-exporter cadvisor`); the DaemonSet replaces it.

## Phase 1 — deploy prod (dark; fleet untouched)

On api01:

```sh
kubectl apply -k ~/osparty-api/k8s/overlays/prod
kubectl -n osparty rollout status deployment/osparty-api --timeout=300s
kubectl -n osparty get pods -o wide   # 3 api pods spread across nodes + redis-0 on api01
```

The namespace is fully up but serves no public traffic: DNS (api.osparty.net) points at api01, where NPM
still owns 80/443, and the idle ingress controller sits on api02. The TLS cert can NOT issue yet
(HTTP-01 needs port 80 at the DNS target) — expect the `osparty-api-tls` Certificate to stay pending
until go-live; that's normal.

Optional smoke test before go-live, from your machine (self-signed cert until issuance, hence `-k`;
requires temporarily allowing your IP → api02:443 in the firewall):

```sh
curl -k --resolve api.osparty.net:443:<api02-public-ip> https://api.osparty.net/api/v1/parties   # -> []
```

## Phase 2 — go live (~minutes; no users, no data to move)

```sh
# On api02 and api03: stop the old api containers
cd ~/osparty-api && docker compose -f docker-compose.node.yml stop api

# On api01, in one sitting — ingress host tunables, stop NPM, move ingress to api01:
sudo tee /etc/sysctl.d/98-osparty-ingress.conf >/dev/null <<'EOF'
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_tw_reuse = 1
EOF
sudo sysctl --system
cd ~/osparty-api && docker compose -f docker-compose.main.yml stop npm api   # release 80/443
kubectl label node api02 osparty.net/ingress-                                # remove from api02
kubectl label node api01 osparty.net/ingress=true                            # ingress now on api01
kubectl -n traefik rollout restart deployment/traefik
# cert-manager solves HTTP-01 for api.osparty.net + monitoring.osparty.net within ~a minute:
kubectl get certificate -A -w
```

**Validation gate** (now on the real domain):

- REST: `curl https://api.osparty.net/api/v1/parties` → `[]`; POST/heartbeat/DELETE round-trip.
- WS: full lifecycle through the plugin (subscribe → snapshot/deltas, host → hosted, drop + resume,
  unhost), including a >60s idle socket surviving.
- Kill an api pod (`kubectl -n osparty delete pod <one>`) → traffic unaffected, pod returns Ready.
- Prometheus targets green (osparty-api ×3, redis), Grafana at `https://monitoring.osparty.net`.
- **Rerun the WS load test** from `deploy/README.md` ("Scaling limits & tuning") — confirm the same
  connection ceilings hold through Traefik.

**Instant rollback** (keep available the first week): on api01
`kubectl label node api01 osparty.net/ingress-` then
`docker compose -f docker-compose.main.yml start npm api` (+ `start api` on api02/api03) — the old stack
is stopped, not removed.

## Phase 3 — switch CI

Commit/push the `k8s/` tree + `.github/workflows/deploy-k8s.yml`, run the **Deploy API (Kubernetes)**
workflow once from the Actions tab to prove the pipeline, then uncomment the `push: branches: [main]`
trigger in `deploy-k8s.yml` and delete `deploy.yml` + `deploy-test.yml`.

Versioning is automatic semver: each run takes the latest `v*.*.*` git tag, bumps the patch (+1), and —
only after a successful rollout — tags the commit and cuts a GitHub release (first-ever run: `1.0.0`).
So after the trigger switch, every push to main deploys as the next patch version with zero manual
steps. For a minor/major bump, run the workflow manually with an explicit version (e.g. `1.1.0`);
auto-bumping continues from there. Redeploying an old version (manual run with that version) skips
re-tagging. Rollback: `kubectl -n osparty rollout undo deployment/osparty-api`, or a manual run with the
previous version.

## Phase 4 — discord-bot migration (separate, after the API is stable)

The bot keeps running as its compose stack on api01 throughout (api pods reach it at `10.0.0.2:8090`,
see the prod overlay patch). Then: manifests + the same one-SSH deploy workflow in the `osparty-discord`
repo, a `Deployment` + `Service` named `osparty-discord` in the `osparty` namespace, flip the overlay
patch to `http://osparty-discord:8090`, add its ServiceMonitor, stop the bot compose stack.

## Phase 5 — decommission

Remove the stopped compose containers/volumes on all hosts, then delete from the repo:
`docker-compose.main.yml`, `docker-compose.node.yml`, `osparty-api-test/`, `deploy/npm/`,
`.env.main.example`, `.env.node.example`, `monitoring/` (config now lives in `k8s/`), and fold this file
into a rewritten `deploy/README.md`. Docker itself can be uninstalled from api02/api03.

## Logs (Loki) + Discord alerts

Logs from every pod are queryable in Grafana (Explore → Loki, or the Logs panel on the OSParty API
dashboard). **Loki** (single-binary, filesystem storage on api01, 14d retention) stores them; a
**Grafana Alloy** DaemonSet tails each node's pods and ships them with `namespace/pod/container/app/node`
labels. Grafana alerts fire into **Discord** via a provisioned contact point set as the root
notification policy — any alert rule created in the Grafana UI notifies Discord automatically.

One-time setup, on api01:

```sh
# Discord webhook for alerts (Server Settings -> Integrations -> Webhooks -> New Webhook, copy URL).
# Must exist BEFORE the monitoring upgrade — Grafana won't start without it:
kubectl -n monitoring create secret generic grafana-env \
  --from-literal=DISCORD_ALERT_WEBHOOK_URL='https://discord.com/api/webhooks/<id>/<token>'

helm repo add grafana https://grafana.github.io/helm-charts
helm upgrade --install loki grafana/loki -n monitoring -f ~/osparty-api/k8s/cluster/loki-values.yaml
helm upgrade --install alloy grafana/alloy -n monitoring -f ~/osparty-api/k8s/cluster/alloy-values.yaml

# Picks up the Loki datasource, the Discord contact point + policy, and the reworked dashboard env:
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  -n monitoring -f ~/osparty-api/k8s/cluster/kube-prometheus-stack-values.yaml
```

Verify: Grafana → Explore → datasource **Loki** → query `{namespace="osparty"}` shows api + bot logs;
Alerting → Contact points → **discord** → Test fires a message into the channel. Alert rules themselves
are created in the UI (Alerting → Alert rules; they persist in Grafana's PVC) — the root policy routes
them all to Discord with no per-rule setup.

## Operations quick reference (api01)

```sh
kubectl -n osparty get pods -o wide                       # what's running where
kubectl -n osparty rollout undo deployment/osparty-api    # rollback a bad deploy
kubectl -n osparty logs deployment/osparty-api -f         # api logs
kubectl top nodes; kubectl top pods -A                    # resource usage
# upgrade k3s deliberately (no auto-updates): re-run the step-1/2/3 installer command on each node,
# one node at a time, agents first.
```

## Known follow-ups

- **End-to-end synthetic probe** (old blackbox `https://api.osparty.net` check): optional — the
  prometheus-blackbox-exporter chart + a `Probe` CRD restores it. In-cluster health is already covered by
  readiness probes.
- **JVM sizing**: watch `container_memory_working_set_bytes` for the api pods; adjust the 2Gi limit /
  `MaxRAMPercentage=50` if GC behaviour differs from the fleet.

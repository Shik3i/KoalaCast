# Infrastructure (`infrastructure`)

Deployment building blocks for self-hosting KoalaCast: container images and the
same-origin reverse proxy.

```text
infrastructure/
├── docker/
│   ├── Dockerfile.api      Multi-stage build for the Go API (CGO + SQLite)
│   └── Dockerfile.web      Multi-stage build for the SvelteKit Node server
└── caddy/
    └── Caddyfile           Reverse proxy: /api → api, everything else → web
```

These are orchestrated by the repo-root [`docker-compose.yml`](../docker-compose.yml).

---

## Container Images

Both Dockerfiles are multi-stage and produce small, non-root runtime images.

| Image | Base (runtime) | Exposes | Notes |
| :--- | :--- | :--- | :--- |
| `Dockerfile.api` | `alpine` | `8080` | Builds with `CGO_ENABLED=1` for SQLite; runs as user `koala`; data in `/app/data` |
| `Dockerfile.web` | `node:20-alpine` | `3000` | Runs the adapter-node server (`node build`); runs as user `koala` |

Build the context from the **repository root** (the Dockerfiles reference
`services/api/` and `apps/web/`):

```bash
docker build -f infrastructure/docker/Dockerfile.api -t koalacast-api .
docker build -f infrastructure/docker/Dockerfile.web -t koalacast-web .
```

Published images (via the [release workflow](../.github/workflows/docker-release.yml))
are multi-arch (`linux/amd64`, `linux/arm64`) and carry signed SLSA provenance
and SBOM attestations on GHCR.

---

## Caddy Reverse Proxy

The [`Caddyfile`](caddy/Caddyfile) makes the whole app a **single origin**, which
is why the web client can use relative `/api/...` URLs:

```
:8080 {
    handle /api/*   { reverse_proxy api:8080 }
    handle /healthz { reverse_proxy api:8080 }
    handle /readyz  { reverse_proxy api:8080 }
    handle          { reverse_proxy web:3000 }
}
```

In `docker-compose.yml` the proxy container's `:8080` is published on host port
**3000**, so you browse the app at <http://localhost:3000>. The `api`/`web`
service names resolve over the Compose network.

---

## Local Deployment

```bash
cp .env.example .env          # set a strong SESSION_SECRET
docker compose up -d          # or: make docker-up
```

- App: <http://localhost:3000> (via Caddy)
- API (direct, for debugging): <http://localhost:8080>

Persistent SQLite data lives in the `koala_data` named volume.

---

## Production Notes

- Terminate TLS at Caddy (or an upstream load balancer) and set `SECURE_COOKIES=true`.
- Set `ORIGIN`/`PUBLIC_BASE_URL` to your public URL.
- Keep `SESSION_SECRET` secret and stable across restarts.

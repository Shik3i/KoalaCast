# Infrastructure (`infrastructure`)

Deployment building blocks for self-hosting KoalaCast: ultra-lightweight multi-stage Docker setup.

```text
infrastructure/
└── docker/
    └── Dockerfile      Multi-stage build compiling SvelteKit static SPA + Go binary into 1 Alpine image
```

Orchestrated by the repo-root [`docker-compose.yml`](../docker-compose.yml).

---

## Container Image

The Dockerfile is multi-stage and produces a compact, non-root Alpine runtime image.

| Image | Base (runtime) | Exposes | Notes |
| :--- | :--- | :--- | :--- |
| `Dockerfile` | `alpine` | `3000` | Builds Node SvelteKit SPA + Go binary (`CGO_ENABLED=1`); runs `/app/koalacast` as user `koala`; data in `/app/data` |

Build the context from the **repository root** (single `Dockerfile` at the repo root):

```bash
docker build -t koalacast .
```

Published images (via the [release workflow](../.github/workflows/docker-release.yml))
are multi-arch (`linux/amd64`, `linux/arm64`) and carry signed SLSA provenance
and SBOM attestations on GHCR.

---

## Native Go Single-Origin Serving

The single Go application binary (`koalacast`) serves both the REST API (`/api/v1/*`) and the static SvelteKit SPA (`/*`) natively on port `3000` with zero external reverse proxies or sidecars:

```
http://localhost:3000
    ├── /api/v1/*   → Go API Handlers
    ├── /healthz    → Go Health Probe
    └── /*          → Go Static File Server (/app/web/build) with index.html SPA fallback
```

---

## Local Deployment

```bash
cp .env.example .env          # set a strong SESSION_SECRET
docker compose up -d          # or: make docker-up
```

- App & API: <http://localhost:3000>

Persistent SQLite data lives in the `koala_data` named volume.

---

## Production Notes

- Set `SECURE_COOKIES=true` when running behind a TLS-terminating reverse proxy.
- Set `PUBLIC_BASE_URL` to your public URL.
- Keep `SESSION_SECRET` secret and stable across restarts.

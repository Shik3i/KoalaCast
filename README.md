<div align="center">

<picture>
  <source type="image/avif" srcset="apps/web/static/icon-128.avif 1x, apps/web/static/icon-256.avif 2x">
  <source type="image/webp" srcset="apps/web/static/icon-128.webp 1x, apps/web/static/icon-256.webp 2x">
  <img src="apps/web/static/icon-128.png" srcset="apps/web/static/icon-128.png 1x, apps/web/static/icon-256.png 2x" alt="KoalaCast Logo" width="128" height="128">
</picture>

# KoalaCast

**A completely free, open-source, privacy-first podcast player for the web.**

Calm, distraction-free listening — with optional, end-to-end cross-device sync.

[![CI](https://github.com/Shik3i/KoalaCast/actions/workflows/ci.yml/badge.svg)](https://github.com/Shik3i/KoalaCast/actions/workflows/ci.yml)
[![Docker Release](https://github.com/Shik3i/KoalaCast/actions/workflows/docker-release.yml/badge.svg)](https://github.com/Shik3i/KoalaCast/actions/workflows/docker-release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Go 1.25](https://img.shields.io/badge/Go-1.25-00ADD8?logo=go&logoColor=white)](services/api)
[![SvelteKit](https://img.shields.io/badge/SvelteKit-5-FF3E00?logo=svelte&logoColor=white)](apps/web)

</div>

---

## Table of Contents

- [Why KoalaCast](#why-koalacast)
- [Features](#features)
- [Quick Start](#quick-start-docker)
- [Architecture](#architecture)
- [Repository Layout](#repository-layout)
- [Development](#development)
- [Configuration](#configuration)
- [Testing](#testing)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [Security](#security)
- [License](#license)

---

## Why KoalaCast

1. **100% free and open source** — MIT licensed, no ads, no tracking, no premium tier.
2. **Local-first** — use the entire app with no account; data lives in your browser's IndexedDB.
3. **Optional cross-device sync** — an account only syncs subscriptions, queue, favorites, and playback progress.
4. **Direct publisher audio** — audio streams straight from the publisher CDN; KoalaCast never proxies or stores it.
5. **RSS as the source of truth** — standard RSS 2.0/Atom plus Podcasting 2.0 tags are preserved.
6. **Self-hosting parity** — a self-hosted instance has exactly the same capabilities as any official one.

---

## Features

| Area | Highlights |
| :--- | :--- |
| **Discovery & Search** | iTunes Top Charts discovery, iTunes/Podcast Index search, add any feed by direct RSS URL |
| **Playback** | Web Audio player, Media Session API, playback-speed control, ms-accurate position tracking, keyboard shortcuts |
| **Library** | Subscriptions, queue, favorites, OPML import/export |
| **Accounts (optional)** | Argon2id hashing, Base32 recovery codes, HttpOnly session cookies, Bearer device tokens |
| **Sync** | Monotonic cursor pull/push, idempotent writes, conflict resolution, full-resync trigger |
| **Admin** | Registration toggle, user suspension, feed-health inspection, manual refresh, system metrics |
| **Backend** | Go + chi, SQLite (WAL), SSRF-safe HTTP transport, background feed worker with ETag/304 + backoff |
| **Ops** | Ultra-lightweight multi-stage Docker image (26MB single Go binary), zero reverse-proxy sidecars, GitHub Actions CI, signed SLSA provenance + SBOM releases |

See the full, always-current breakdown in [docs/current-status.md](docs/current-status.md).

---

## Quick Start (Docker)

```bash
# 1. Clone
git clone https://github.com/Shik3i/KoalaCast.git
cd KoalaCast

# 2. Configure — set a strong SESSION_SECRET (32+ chars)
cp .env.example .env

# 3. Launch single Go application binary container on port 3000
docker compose up -d
```

Open the app at **<http://localhost:3000>**.

> The single Go application binary (`koalacast`) serves both the REST API (`/api/v1/*`) and the static SvelteKit SPA (`/*`) natively on port `3000` with zero external reverse proxies or sidecars.

Prefer `make`? See the [Makefile targets](#development): `make docker-up`, `make docker-down`.

---

## Architecture

```text
                                 http://localhost:3000
                                          │
                               ┌──────────▼──────────┐
                               │   Go REST API       │
                               │   (chi router)      │
                               ├─────────────────────┤
                               │ Native Static SPA   │
                               │ Server (/web/build) │
                               └──────────┬──────────┘
                                          │
                        ┌─────────────────┼─────────────────┐
                        │                 │                 │
                ┌───────▼───────┐ ┌───────▼───────┐ ┌───────▼───────┐
                │ SQLite (WAL)  │ │ Feed Worker   │ │ In-Memory RAM │
                │ Database      │ │ Pool          │ │ LRU Cache     │
                └───────────────┘ └───────┬───────┘ └───────────────┘
                                          │
    Web / native audio player ────────────┴────────▶ Direct publisher audio (CDN)
```

Deep dives live in [docs/](docs/):

- [Architecture & config precedence](docs/architecture/overview.md)
- [Sync engine protocol](docs/sync-protocol/specification.md)
- [Feed compatibility & parsing](docs/feed-compatibility/rss-spec.md)
- [Privacy policy & data retention](docs/privacy/privacy-policy.md)
- [Future Android architecture](docs/android-architecture.md)

---

## Repository Layout

```text
KoalaCast/
├── apps/web/            SvelteKit 5 web client (adapter-static SPA) → apps/web/README.md
├── services/api/        Go REST API, SQLite, workers, SPA server    → services/api/README.md
├── packages/openapi/    OpenAPI 3 contract for the REST API        → packages/openapi/README.md
├── infrastructure/      Minimal multi-stage Alpine Dockerfile       → infrastructure/README.md
├── docs/                Architecture, sync, privacy, feed specs    → docs/README.md
├── testdata/            Sample RSS feeds for tests                 → testdata/README.md
├── docker-compose.yml   Single-command self-host stack
├── Makefile             Developer task runner (make help)
└── .github/workflows/   CI and Docker release pipelines
```

Every top-level directory has its own `README.md` describing its contents and conventions.

---

## Development

### Requirements

- **Go** 1.25+
- **Node.js** 20+
- **Docker** 24+ with Compose (optional, for the container workflow)

### Common tasks (`make help`)

| Target | Description |
| :--- | :--- |
| `make build` | Build the Go API binary and the SvelteKit static SPA bundle |
| `make dev-api` | Run the Go API on `:3000` |
| `make dev-web` | Run the SvelteKit dev server on `:5173` (proxies `/api` → `:3000`) |
| `make test` | Go tests with `-race` + `svelte-check` |
| `make fmt` / `make vet` | Format Go sources / run `go vet` |
| `make docker-build` / `make docker-up` / `make docker-down` | Container workflow |
| `make clean` | Remove build artifacts and local databases |

### Manual dev loop

```bash
# Terminal 1 — backend on :3000
cd services/api
SESSION_SECRET=dev-secret-with-at-least-32-characters go run ./cmd/server

# Terminal 2 — frontend on :5173 (Vite proxies /api to :3000)
cd apps/web
npm install
npm run dev
```

---

## Configuration

The backend is configured entirely through environment variables. Copy [`.env.example`](.env.example) to `.env` and adjust. Key settings:

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `PORT` | `3000` | Server listen port |
| `SESSION_SECRET` | — | **Required.** 32+ byte secret for session signing |
| `PEPPER_SECRET` | empty | Optional HMAC-SHA256 secret key for Argon2id password hashing |
| `DATABASE_PATH` | `./data/koalacast.db` | SQLite database file |
| `KC_REGISTRATION_ENABLED` | unset | Hard override for account registration (else DB-controlled) |
| `PODCAST_INDEX_KEY` / `_SECRET` | empty | Optional Podcast Index API creds (iTunes used as fallback) |
| `FEED_WORKER_CONCURRENCY` | `5` | Background feed-refresh workers |
| `FEED_MAX_RESPONSE_BYTES` | `10485760` | Max RSS body size (SSRF/DoS guard) |

Full precedence rules: [docs/architecture/overview.md](docs/architecture/overview.md).

---

## Testing

```bash
# Backend — unit + integration with the race detector
cd services/api && go test -race ./...

# Frontend — type checking
cd apps/web && npm run check
```

CI runs the same checks plus `go vet`, `gofmt`, OpenAPI linting, and Docker builds on every push and PR — see [.github/workflows/ci.yml](.github/workflows/ci.yml).

---

## Documentation

- **Users / self-hosters:** start with [Quick Start](#quick-start-docker) and [Configuration](#configuration).
- **Contributors:** read [CONTRIBUTING.md](CONTRIBUTING.md) and the per-directory READMEs.
- **Specs:** browse the [docs/](docs/) index.

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow, coding standards, and commit conventions, and abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Security

Found a vulnerability? Please **do not** open a public issue — follow the disclosure process in [SECURITY.md](SECURITY.md).

---

## License

Released under the **MIT License** — see [LICENSE](LICENSE).

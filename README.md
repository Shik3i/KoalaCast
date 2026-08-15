<div align="center">

<picture>
  <source type="image/avif" srcset="apps/web/static/icon-128.avif 1x, apps/web/static/icon-256.avif 2x">
  <source type="image/webp" srcset="apps/web/static/icon-128.webp 1x, apps/web/static/icon-256.webp 2x">
  <img src="apps/web/static/icon-128.png" srcset="apps/web/static/icon-128.png 1x, apps/web/static/icon-256.png 2x" alt="KoalaCast Logo" width="128" height="128">
</picture>

# KoalaCast

**A completely free, open-source, privacy-first podcast player for web and Android.**

Calm, distraction-free listening — with optional account-backed cross-device sync.

[![CI](https://github.com/Shik3i/KoalaCast/actions/workflows/ci.yml/badge.svg)](https://github.com/Shik3i/KoalaCast/actions/workflows/ci.yml)
[![Docker Release](https://github.com/Shik3i/KoalaCast/actions/workflows/docker-release.yml/badge.svg)](https://github.com/Shik3i/KoalaCast/actions/workflows/docker-release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Go 1.26.5](https://img.shields.io/badge/Go-1.26.5-00ADD8?logo=go&logoColor=white)](services/api)
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

1. **100% free and open source** — MIT licensed, no ads, no behavioral tracking, no premium tier. The Android Cast SDK's anonymous product telemetry is disclosed in the privacy policy.
2. **Local-first** — use the entire app with no account; data lives in browser IndexedDB or Android Room storage.
3. **Optional cross-device sync** — an account syncs subscriptions, favorites, playback progress, and listening statistics.
4. **Direct publisher audio by default** — playback streams straight from the publisher CDN. Self-hosters can opt into an audio relay for browser effects/downloads blocked by publisher CORS.
5. **RSS as the source of truth** — standard RSS 2.0/Atom plus Podcasting 2.0 tags are preserved.
6. **Self-hosting parity** — a self-hosted instance has exactly the same capabilities as any official one.

---

## Features

| Area | Highlights |
| :--- | :--- |
| **Discovery & Search** | iTunes Top Charts discovery, iTunes/Podcast Index search, multi-select preferred/hidden genres, per-podcast hiding, add any feed by direct RSS URL |
| **Languages** | Spoken-language filtering (not just storefront region) for Discover and Search, language + genre search filters, fully translated English/German interface (add a language with one JSON file) |
| **Playback** | Web Audio player, Media Session and Remote Playback APIs, playback-speed control, per-podcast controls, live scrubbing with chapter markers, jump-back after a large seek, a sleep timer that counts listening time rather than wall-clock time, a transcript that follows the playhead, episode sharing with a timestamped link, listening-time tracking, keyboard shortcuts |
| **Library** | Subscriptions with folders, queue plus reusable named queues, smart queues built from saved rules, favorites, timestamp bookmarks, OPML import/export plus an optional auto-updating OPML backup file |
| **Accounts (optional)** | Argon2id hashing, Base32 recovery codes, HttpOnly session cookies, Bearer device tokens |
| **Sync** | Subscriptions, favorites, playback state, listening sessions, queue, podcast settings, and global settings via monotonic cursor pull/push and idempotent writes; settings merge per field, so two devices editing different preferences do not revert each other |
| **Statistics** | Private personal listening analytics plus separately opt-in global aggregates and listener leaderboard |
| **Offline** | Downloads with an enforced storage budget, retention policy and parallel-transfer limit; installable as a standalone app; new-episode checks continue in the background where the browser allows it |
| **Customization** | Light/dark/system modes, nine palettes with Fjord as default, resizable side rails, per-show playback preferences |
| **Admin** | Registration toggle, user suspension, feed-health inspection, manual refresh, system metrics |
| **Backend** | Go + chi, SQLite (WAL), SSRF-safe HTTP transport, background feed worker with ETag/304 + backoff |
| **Ops** | Multi-stage single-binary Docker image, zero reverse-proxy sidecars, GitHub Actions CI, signed SLSA provenance + SBOM releases |
| **Android** | Native Kotlin/Compose/Media3 client with Room, downloads, Android Auto/Wear browsing, Chromecast output, widget and HTTPS-only release networking |

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
- [Internationalization & language filtering](docs/i18n.md)
- [Roadmap](docs/roadmap.md)
- [Android architecture](docs/android-architecture.md)

---

## Repository Layout

```text
KoalaCast/
├── apps/
│   ├── web/             SvelteKit 5 static SPA                    → apps/web/README.md
│   └── android/         Native Kotlin/Compose/Media3 client       → apps/android/README.md
├── services/api/        Go REST API, SQLite, workers, SPA server    → services/api/README.md
├── packages/openapi/    OpenAPI 3 contract for the REST API        → packages/openapi/README.md
├── docs/                Architecture, sync, privacy, feed specs    → docs/README.md
├── testdata/            Sample RSS feeds for tests                 → testdata/README.md
├── tools/               Developer-only asset maintenance scripts   → tools/README.md
├── Dockerfile           Multi-stage Node/Go/Alpine production image
├── docker-compose.yml   Single-command self-host stack
├── Makefile             Developer task runner (make help)
└── .github/workflows/   CI and Docker release pipelines
```

Major code, contract, documentation and fixture directories have a focused
`README.md`; repository-wide tooling remains documented here.
Directory indexes: [applications](apps/README.md),
[services](services/README.md), [packages](packages/README.md),
[documentation](docs/README.md), [test fixtures](testdata/README.md) and
[developer tools](tools/README.md).

---

## Development

### Requirements

- **Go** 1.26.5+
- **Node.js** 24+
- **Docker** 24+ with Compose (optional, for the container workflow)

### Common tasks (`make help`)

| Target | Description |
| :--- | :--- |
| `make build` | Build the Go API binary and the SvelteKit static SPA bundle |
| `make dev-api` | Run the Go API on `:3000` |
| `make dev-web` | Run the SvelteKit dev server on `:5173` (proxies `/api` → `:3000`) |
| `make test` | Go tests with `-race`; web unit, type, docs, release-policy, translation and SEO checks |
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
| `FEED_MAX_RESPONSE_BYTES` | `33554432` | Max RSS body size in bytes (32 MiB; SSRF/DoS guard) |
| `FEED_MAX_STORED_EPISODES` | `200` | Recent metadata-cache rows retained per podcast; rows referenced by user state are preserved |
| `FEED_REFRESH_INTERVAL_MS` | `3600000` | How long a healthy feed waits before the background worker rechecks it (15 min – 24 h). Governs how promptly new-episode notifications arrive |
| `WEB_PUSH_VAPID_PUBLIC_KEY` / `_PRIVATE_KEY` | empty | Enables server-sent browser notifications; generate once with `cd services/api && go run ./cmd/vapid` |
| `WEB_PUSH_VAPID_SUBJECT` | `PUBLIC_BASE_URL` | VAPID contact URI (`https:` or `mailto:`) |
| `KC_AUDIO_EFFECTS_PROXY_ENABLED` | `true` | Relay fallback for CORS-blocked browser effects/downloads; set `false` to avoid relay bandwidth |

Full precedence rules: [docs/architecture/overview.md](docs/architecture/overview.md).

---

## Testing

```bash
# Backend — unit + integration with the race detector
cd services/api && go test -race ./...

# Frontend — unit/UI tests, types, docs, release policy, translations, SEO, build
cd apps/web && npm test && npm run test:ui && npm run check && npm run check:docs && npm run check:release-policy && npm run check:i18n && npm run check:seo && npm run build
```

The path-filtered CI suite runs these checks plus `go vet`, `gofmt`, OpenAPI
linting and a Docker runtime smoke test when web, API, contract, container or
workflow inputs change. Android release gates run on `android-v*` tags and
manual dispatch; Android changes must pass the documented Gradle gate locally.
See [.github/workflows/ci.yml](.github/workflows/ci.yml) and
[.github/workflows/android-release.yml](.github/workflows/android-release.yml).

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

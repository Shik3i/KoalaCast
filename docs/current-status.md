# KoalaCast Current Implementation Status

**Last Updated:** July 24, 2026  
**License:** MIT License (`Copyright (c) 2026 Timo Schmidt (Shik3i)`)

---

## 1. Feature Matrix Overview

| Component / Feature | Implementation Status | Description |
| :--- | :--- | :--- |
| **Monorepo Layout & MIT License** | ✅ **Implemented** | Complete directory layout, MIT License, README, `.env.example`, `.gitignore`. |
| **Go REST API Server** | ✅ **Implemented** | Chi router, JSON logging (`slog`), request context tracing (`X-Request-ID`), `/healthz` and `/readyz` probes. |
| **SQLite DB & Migrations** | ✅ **Implemented** | SQLite WAL mode, foreign keys (`PRAGMA foreign_keys=ON;`), busy timeout (`5000ms`), strict `CHECK` constraints, normalized usernames, stable episode identity keys. |
| **SSRF Network Protection** | ✅ **Implemented** | Custom `http.Transport` with DNS resolution in `DialContext`, blocking loopback, private IPv4/IPv6, link-local, cloud metadata (`169.254.169.254`), CGNAT, IPv4-mapped IPv6, and redirect loops. |
| **RSS & Atom Feed Parser** | ✅ **Implemented** | Parser supporting RSS 2.0 & Atom 1.0, Podcasting 2.0 tags (`chapters`, `transcript` arrays), `content:encoded`, iTunes durations, zero-time fallback for missing dates, deterministic stable episode identity resolution. |
| **Background Feed Update Worker**| ✅ **Implemented** | Worker pool checking subscribed feeds, ETag / Last-Modified 304 handling, exponential backoff, error tracking (`consecutive_error_count`), and episode persistence. |
| **Podcast Search & Direct RSS Feed**| ✅ **Implemented** | `/api/v1/podcasts/search` with Podcast Index integration / graceful fallback, `/api/v1/podcasts/feed` direct RSS URL addition, podcast details, and episode pagination. |
| **Same-Origin Caddy Proxy** | ✅ **Implemented** | Caddy routing configuration mapping `/` to web app and `/api/v1/*` to Go API. |
| **Docker Deployment Setup** | ✅ **Implemented** | Multi-stage Dockerfiles (`Dockerfile.api`, `Dockerfile.web`) and single-command `docker-compose.yml`. |
| **SvelteKit Web Player & UI** | ✅ **Implemented** | SvelteKit + Svelte 5 web app with Forest Green design system, Media Session API integration, millisecond position tracking, speed controls, discovery, library, and settings. |
| **IndexedDB Local Storage Engine** | ✅ **Implemented** | Account-free Local Mode storing subscriptions, queue, playback progress, and favorites strictly inside browser IndexedDB. |
| **Accounts, Security & Auth** | ✅ **Implemented** | User registration, Argon2id password hashing, grouped 32-byte Base32 recovery code generation (`AAAA-BBBB-...`), HttpOnly session cookies, Bearer device tokens, session revocation. |
| **Cross-Device Sync Protocol** | ✅ **Implemented** | Monotonic user cursor allocation (`user_sync_cursors`), incremental `/api/v1/sync` pull, idempotent push, 410 Gone full-resync trigger, playback state conflict resolution rules. |
| **OPML Import / Export** | ✅ **Implemented** | OPML import with SSRF validation, duplicate feed handling, partial success reporting, and standard OPML export. |
| **Admin Interface** | ✅ **Implemented** | Admin dashboard (`/admin`), registration toggle (honoring `KC_REGISTRATION_ENABLED` env override), user suspension, session revocation, feed health inspection, manual feed refresh, and system metrics. |
| **GitHub Actions CI Workflow**| ✅ **Implemented** | `.github/workflows/ci.yml` running Go tests with `-race`, `go vet`, frontend `npm ci`, `npm run check`, `npm run build`, OpenAPI linting, and Docker builds. |
| **Native Android Client** | ⏳ **Planned** | Kotlin + Jetpack Compose + Media3 Android application detailed in `docs/android-architecture.md`; planned for future release. |

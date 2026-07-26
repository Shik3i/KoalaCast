# KoalaCast Current Implementation Status

**Last Updated:** July 25, 2026  
**License:** MIT License (`Copyright (c) 2026 Timo Schmidt (Shik3i)`)

---

## 1. Feature Matrix Overview

| Component / Feature | Implementation Status | Description |
| :--- | :--- | :--- |
| **Monorepo Layout & MIT License** | ✅ **Implemented** | Complete monorepo layout, MIT License, READMEs across all packages, `.env.example`, `.gitignore`. |
| **Go REST API Server** | ✅ **Implemented** | Chi router, JSON logging (`slog`), request context tracing (`X-Request-ID`), `/healthz` and `/readyz` probes. |
| **SQLite DB & Migrations** | ✅ **Implemented** | SQLite WAL mode, foreign keys (`PRAGMA foreign_keys=ON;`), busy timeout (`5000ms`), strict `CHECK` constraints, normalized usernames, stable episode identity keys. |
| **SSRF Network Protection** | ✅ **Implemented** | Custom `http.Transport` with DNS resolution in `DialContext`, blocking loopback, private IPv4/IPv6, link-local, cloud metadata (`169.254.169.254`), CGNAT, IPv4-mapped IPv6, and redirect loops. |
| **RSS & Atom Feed Parser** | ✅ **Implemented** | Parser supporting RSS 2.0 & Atom 1.0, Podcasting 2.0 tags (`chapters`, `transcript` arrays), `content:encoded`, iTunes durations, zero-time fallback for missing dates, deterministic stable episode identity resolution. |
| **Background Feed Update Worker**| ✅ **Implemented** | Worker pool checking subscribed feeds, ETag / Last-Modified 304 handling, exponential backoff, error tracking (`consecutive_error_count`), and episode persistence. |
| **Podcast Search & Direct RSS Feed**| ✅ **Implemented** | `/api/v1/podcasts/search` with Podcast Index integration / graceful fallback, `/api/v1/podcasts/feed` direct RSS URL addition, podcast details, and episode pagination. |
| **Single Go Binary & Static Server** | ✅ **Implemented** | Go REST API serving static SvelteKit SPA assets (`@sveltejs/adapter-static`) directly on port 3000 with zero external reverse proxies or sidecars. |
| **RAM LRU Image Proxy & Cache** | ✅ **Implemented** | In-memory 100MB RAM LRU cache (`container/list`) with Catmull-Rom downscaling and `singleflight` thundering herd protection. |
| **Docker Deployment Setup** | ✅ **Implemented** | Ultra-lightweight multi-stage Dockerfile (26MB Alpine single Go binary), named volume `koala_data`, and single-command `docker-compose.yml` supporting external proxy networks (`caddy_net`). |
| **SvelteKit Web Player & UI** | ✅ **Implemented** | SvelteKit + Svelte 5 web app with Forest Green design system, sticky scroll-shrink header, extracted keyboard shortcuts modal (`ShortcutsModal.svelte`), Media Session API integration, position tracking, speed controls, discovery, library, and settings. |
| **Dedicated Auth & Account Routes** | ✅ **Implemented** | Dedicated `/login`, `/register`, and `/account` routes with active session management and device revocation. |
| **Data-Driven Privacy Policy** | ✅ **Implemented** | Dedicated `/privacy` route backed by structured data module (`src/lib/data/privacy.ts`) documenting Hetzner EU hosting, 7-day logs, IP subnet truncation, and local IndexedDB storage. |
| **IndexedDB Local Storage Engine** | ✅ **Implemented** | Account-free Local Mode storing subscriptions, queue, playback progress, and favorites strictly inside browser IndexedDB. |
| **Accounts, Security & Auth** | ✅ **Implemented** | User registration, Argon2id password hashing (`m=64MB, t=3, p=2`), thread-safe HMAC-SHA256 Pepper secret support with legacy fallback & auto-upgrade of seeded admin hashes, Base32 recovery code generation (`AAAA-BBBB-...`), HttpOnly session cookies, Bearer device tokens, session revocation. |
| **Cross-Device Sync Protocol** | ✅ **Implemented** | Monotonic user cursor allocation (`user_sync_cursors`), incremental `/api/v1/sync` pull, idempotent push, 410 Gone full-resync trigger, playback state conflict resolution rules. |
| **OPML Import / Export** | ✅ **Implemented** | OPML import with SSRF validation, duplicate feed handling, partial success reporting, and standard OPML export. |
| **Admin Interface** | ✅ **Implemented** | Admin dashboard (`/admin`), registration toggle (honoring `KC_REGISTRATION_ENABLED` env override), user suspension, session revocation, feed health inspection, manual feed refresh, and system metrics. |
| **GitHub Actions CI & Release** | ✅ **Implemented** | `.github/workflows/ci.yml` running Go tests with `-race`, `go vet`, frontend `npm ci`, `npm run check`, `npm run build`, OpenAPI linting, and Docker release builds on tags (`v*`). |
| **Spoken-Language Filtering** | ✅ **Implemented** | Discover and Search filtered by the feed's actual language rather than the iTunes storefront region. Language resolved from the RSS `<language>` tag or Podcast Index where available, else a stopword heuristic (`internal/lang`); undetectable feeds are kept rather than hidden. Chart requests over-fetch so a filtered page still fills. |
| **Search Filters** | ✅ **Implemented** | Language and genre filter chips on `/search`, pre-selected from the listener's settings with "clear filters" to search everything, and a reset back to settings defaults. |
| **UI Internationalization** | ✅ **Implemented** | Dependency-free i18n: JSON catalogues per language (contributor- and Weblate-friendly), CLDR plurals via `Intl.PluralRules`, lazy-loaded locale chunks awaited before first paint, `MessageKey` types derived from the source catalogue, and an in-app interface-language picker. English + German at 369 keys / 100% parity across every route and component. CI enforces placeholder and plural integrity (`npm run check:i18n`); `find-untranslated.mjs` audits for hardcoded strings. Legal text is deliberately English-only. See `docs/i18n.md`. |
| **Native Android Client** | 🏗️ **In Progress (P0/P1)** | Kotlin + Jetpack Compose multi-module app in `apps/android`. Shipped: Gradle convention plugins, Hilt, Compose navigation, DataStore, first-run **server selection** with `/healthz` validation and runtime-switchable base URL, the "Quiet Edition" design system (bundled OFL fonts + MIT Phosphor glyphs, English/German strings), and read-only Discover / Search / Podcast / Episode / Settings screens. Cover art is routed through the listener's own instance by default; audio is never proxied. Next: Media3 playback (P2), Room local-first storage (P3), downloads (P4). See `apps/android/README.md`. |

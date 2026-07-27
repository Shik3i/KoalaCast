# KoalaCast Current Implementation Status

**Last updated:** July 27, 2026
**License:** MIT

This document records shipped behavior. Proposed work belongs in
[roadmap.md](roadmap.md) or [`api_todo.md`](../api_todo.md).

## Web and API

| Area | Status | Current behavior |
| :--- | :--- | :--- |
| **Web application** | Implemented | SvelteKit 5 static SPA, served by the Go application. Responsive three-column layout with resizable/collapsible side rails and mobile navigation. |
| **Discovery and search** | Implemented | iTunes charts/search, optional Podcast Index search, direct RSS addition, language/genre filters, clear/reset behavior, and subscription-aware Inbox. |
| **Playback** | Implemented | HTML audio + Media Session, speed control, skip controls, sleep timer, silence trimming, volume boost, chapters, transcripts, queue, keyboard shortcuts, and progress ring controls. |
| **Local-first storage** | Implemented | Subscriptions, queue, favorites, playback progress, listening sessions, and preferences work without an account in IndexedDB/LocalStorage. |
| **Accounts and sync** | Implemented with documented boundaries | Username/password accounts, recovery codes, web sessions, Android device tokens, and incremental sync for subscriptions, favorites, playback state, and listening sessions. Queue and per-podcast settings remain local until their server materialization is implemented; see [`api_todo.md`](../api_todo.md). |
| **Statistics** | Implemented | Personal listening duration, sessions, podcasts, speed and time-saved metrics. Signed-in users can separately opt into global aggregates, podcast rankings, and the listener leaderboard; participation defaults to off. |
| **Themes and accessibility** | Implemented | System/light/dark modes, nine palettes (Fjord default; Eucalyptus retained), scalable/resizable layout, reduced motion, focus treatment, tooltips, accessible names, and English/German UI. |
| **SEO and sharing** | Implemented | Canonical/robots metadata, sitemap with Git-derived `lastmod`, WebSite/SoftwareApplication JSON-LD, `llms.txt`, `llms-full.txt`, and 1200×630 Open Graph/Twitter artwork. |
| **Admin** | Implemented | Registration policy, users, suspension, session revocation, feed health/manual refresh, and system metrics. |
| **Feeds and metadata** | Implemented | RSS 2.0/Atom, Podcasting 2.0 chapters/transcripts, stable episode identity, ETag/304 refresh, backoff, SSRF protection, response limits, and privacy-preserving metadata/image proxying. |
| **API and persistence** | Implemented | Go/chi API, SQLite WAL, embedded migrations, health/readiness endpoints, request IDs, rate limiting, and static SPA serving from one process. |

## Android

The native Kotlin/Compose/Media3 application has P0–P6 shipped: onboarding and
server selection, discovery/search, playback, Room local-first library,
resumable downloads, Inbox, profile statistics, accounts, device-token sync,
OPML, and global statistics. Remaining P7 platform work includes Android Auto,
widgets, chapters, dynamic artwork palettes, advanced download policies, and
additional UI/integration test coverage. The detailed live checklist is
[`apps/android/README.md`](../apps/android/README.md).

## Delivery and quality gates

- CI runs Go formatting, vet and race tests; Svelte checks and unit tests;
  translation, documentation and SEO audits; OpenAPI linting; Docker build and
  runtime smoke tests; and an arm64 cross-compile check.
- `v*` tags publish multi-architecture GHCR images with provenance and SBOM
  attestations. They do not create GitHub Releases.
- `android-v*` tags build signed Android packages, verify signatures, attest
  artifacts, and are the only tags that create a GitHub Release.
- GitHub-hosted action majors were reviewed against their official current
  documentation on July 27, 2026.

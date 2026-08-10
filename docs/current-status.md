# KoalaCast Current Implementation Status

**Last updated:** August 10, 2026
**License:** MIT

This document records shipped behavior. Proposed work belongs in
[roadmap.md](roadmap.md).

## Web and API

| Area | Status | Current behavior |
| :--- | :--- | :--- |
| **Web application** | Implemented | SvelteKit 5 static SPA, served by the Go application. Responsive three-column layout with resizable/collapsible side rails and a four-destination mobile navigation matching Android. |
| **Discovery and search** | Implemented | iTunes charts/search, optional Podcast Index search, direct RSS addition, language filters, multi-select preferred/hidden genres, account-synced per-podcast hiding, clear/reset behavior, and subscription-aware Inbox. |
| **Playback** | Implemented | HTML audio + Media Session, compatible-browser Remote Playback, speed control, skip controls, silence trimming, volume boost, selectable audio visualisers, chapters, transcripts, queue, keyboard shortcuts, timestamp bookmarks, and handoff links. The timeline scrubs live with a hover readout and chapter markers, a jump of fifteen seconds or more offers the position it came from, the previous-track control steps back through the played history, the full-screen view carries a reorderable queue, and the transcript follows the playhead. The sleep timer counts listening time, not wall-clock time, so a pause does not spend it. Web Audio effects need CORS, so blocked enclosures are first retried against the host their redirect chain resolves to and only fall back to the audio relay when that host refuses too. |
| **Local-first storage** | Implemented | Subscriptions with folders, queue plus reusable named queues, smart queues (saved rules evaluated over cached episodes), favorites, timestamp bookmarks, playback progress, listening sessions, and preferences work without an account in IndexedDB/LocalStorage. Deleting local data removes the downloaded audio and every stored preference, not only the database rows. |
| **Accounts and sync** | Implemented with documented boundaries | Username/password accounts, recovery codes, web sessions, Android device tokens, and incremental sync for subscriptions, favorites, playback state, listening sessions, queue, podcast settings, and global settings. Each client carries the other's unknown settings keys through a push, so a client that does not understand a key no longer deletes it from the server, and the settings blob is merged per field rather than as one lump — two devices editing different preferences no longer revert each other. Unreadable records are skipped and counted instead of wedging the pull. See [sync-protocol/specification.md](sync-protocol/specification.md). The default Inbox mode for new subscriptions is account-scoped; each podcast can override it. Named queues, folders, and timestamp bookmarks remain local. |
| **Statistics** | Implemented | Personal listening duration, sessions, podcasts, speed and time-saved metrics. Signed-in users can separately opt into global aggregates, podcast rankings, and the listener leaderboard; participation defaults to off. |
| **Themes and accessibility** | Implemented | System/light/dark modes, nine palettes (Fjord default; Eucalyptus retained), selectable start screen, configurable artwork privacy, download policies now honoured by both clients, scalable/resizable layout, reduced motion, focus treatment, tooltips, accessible names, and English/German UI. |
| **SEO and sharing** | Implemented | Canonical/robots metadata, sitemap with Git-derived `lastmod`, WebSite/SoftwareApplication JSON-LD, `llms.txt`, `llms-full.txt`, and 1200×630 Open Graph/Twitter artwork. |
| **Admin** | Implemented | Registration policy, users, suspension, session revocation, feed health/manual refresh, and system metrics including SQLite main/WAL and metadata-payload breakdowns. |
| **Feeds and metadata** | Implemented | RSS 2.0/Atom, Podcasting 2.0 chapters/transcripts, stable episode identity, ETag/304 refresh, backoff, SSRF protection, response limits, bounded recent-episode retention, on-demand refresh for regular subscriptions, notification-only background refresh, and privacy-preserving metadata/image proxying. |
| **Notifications** | Implemented | VAPID Web Push wakes the service worker and shows system notifications while the site is closed; foreground notifications remain available as a fallback, and turning them off unsubscribes the browser and deletes the server-side registration. Where Periodic Background Sync exists (Chromium, installed app), the worker also checks watched shows from a small page-maintained mirror without any tab being open. |
| **Downloads (web)** | Implemented | Streamed to the Cache API with a queue, an enforced storage budget, retention policy, parallel-transfer limit and a metered-connection check for automatic downloads. Auto-download honours the configured episode count. |
| **Installation and backup** | Implemented | Installable as a standalone app where the browser offers it, and — where the File System Access API exists — an OPML file chosen once is kept in step with the subscription list. |
| **API and persistence** | Implemented | Go/chi API, SQLite WAL, embedded migrations, health/readiness endpoints, request IDs, rate limiting, and static SPA serving from one process. |

## Android

The native Kotlin/Compose/Media3 application has P0–P7 shipped: onboarding and
server selection, discovery/search with preferred/hidden genres and per-podcast
hiding, playback, Room local-first library,
resumable downloads, Inbox, profile statistics, accounts, device-token sync,
OPML, global statistics, Android Auto/Wear browse support, a home-screen widget,
chapters, Chromecast output transfer, dynamic artwork palettes, advanced download policies, timestamp
bookmarks, handoff links, named queues, and podcast folders. Destructive
actions sit behind a menu or a confirmation, the start screen is selectable,
sign-in is offered during onboarding, and the player fits one screen with an
optional amplitude visualiser (off by default) fed by a Media3 tap on the
app's own decoded PCM. The sleep timer counts listening time rather than
wall-clock time, the playback-speed range matches the web client, and the
download concurrency limit cannot be exceeded by changing it mid-transfer.
Additional UI/integration test coverage remains desirable, and the home-screen
widget has not been exercised on a device since its broadcast was
authenticated. The detailed live checklist is
[`apps/android/README.md`](../apps/android/README.md).

## Delivery and quality gates

- CI runs Go formatting, vet and race tests; Svelte checks and unit tests;
  translation, documentation and SEO audits; OpenAPI linting; Docker build and
  runtime smoke tests; and an arm64 cross-compile check.
- `v*` tags publish multi-architecture GHCR images with provenance and SBOM
  attestations. They do not create GitHub Releases.
- `android-v*` tags build signed APK and AAB packages, verify signatures, attest
  artifacts, and are the only tags that create a GitHub Release.
- GitHub-hosted action majors were reviewed against their official current
  documentation on July 27, 2026.

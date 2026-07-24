# KoalaCast — Native Android Client

> **Status:** 📋 Spec / not started. This document is the single source of truth for
> the next agent (or contributor) who begins the Android app. Read it fully before
> writing any code. It defines the product bar, the tech stack, the backend
> contract, and a phased, checklist-driven roadmap.
>
> Companion docs: [`docs/android-architecture.md`](../../docs/android-architecture.md)
> (high-level architecture), [`docs/sync-protocol/specification.md`](../../docs/sync-protocol/specification.md)
> (sync DTOs), [`apps/web/`](../web) (reference implementation — feature parity target).

---

## 0. Product vision (why this app must be great)

KoalaCast is a **completely free, open-source, privacy-first** podcast player. The
Android app is not a webview wrapper — it is a **first-class native client** that
must feel faster and calmer than Pocket Casts / Spotify / AntennaPod while keeping
the project's principles:

- **Local-first.** The entire app works with **no account**. Data lives on-device
  (Room). An account is optional and only enables cross-device sync.
- **No tracking, no ads, no premium tier.** Ever.
- **Direct-to-publisher audio.** Episode audio streams/downloads **straight from the
  publisher's enclosure URL** — it never passes through KoalaCast servers.
- **Self-hostable.** Anyone can point the app at their own KoalaCast server.

### USP (what makes us different)
1. **True local-first + optional E2E-friendly sync** — most FOSS players are either
   local-only or account-required. We are both.
2. **Self-host server picker built into onboarding** — first-class, not an afterthought.
3. **Dynamic per-show theming** — UI recolors from cover art (already in web; port it).
4. **Privacy by architecture** — no audio proxy, no analytics, revocable device tokens.
5. **Calm, distraction-free design with real "wow" motion** — see §7.

---

## 1. Tech stack (decided)

| Concern | Choice | Notes |
| :-- | :-- | :-- |
| Language | **Kotlin** (latest stable) | Coroutines + Flow throughout. |
| UI | **Jetpack Compose** + Material 3 | Dynamic color (Material You) + our own show-accent theming. |
| Architecture | **MVVM / MVI**, unidirectional data flow | `ViewModel` + immutable UI state + `StateFlow`. |
| DI | **Hilt** | |
| Audio | **AndroidX Media3** (`ExoPlayer` + `MediaLibraryService`/`MediaSessionService`) | Background playback, notification, lockscreen, Android Auto, Bluetooth/media buttons. |
| Local DB | **Room** | Millisecond precision (`Long`). Mirrors web IndexedDB stores. |
| Preferences | **DataStore (Proto or Prefs)** | Server URL, theme, playback speed, download-on-wifi-only, etc. |
| Networking | **Retrofit + OkHttp** (or Ktor client) + **kotlinx.serialization** | Talks to `/api/v1/*`. |
| Background work | **WorkManager** | Periodic feed refresh, sync, download queue, auto-download. |
| Images | **Coil** | Cover art + palette extraction for show-accent. |
| Downloads | **Media3 `DownloadManager`** (or OkHttp + WorkManager) | Offline episodes; see §5. |
| Testing | JUnit, Turbine (Flow), Compose UI tests, Robolectric, MockWebServer | |
| Min SDK | **26 (Android 8.0)** target latest | Confirm before building. |
| Build | Gradle (Kotlin DSL), version catalog (`libs.versions.toml`) | |

Module layout (suggested):
```
apps/android/
  app/                    # Compose UI, navigation, DI wiring
  core/
    core-model/           # domain models
    core-data/            # repositories, Room, DataStore
    core-network/         # Retrofit API, DTOs, auth interceptor
    core-player/          # Media3 service + controller
    core-download/        # download manager + WorkManager workers
    core-ui/              # design system (theme, show-accent, components)
  feature/
    feature-discover/  feature-search/  feature-library/
    feature-podcast/   feature-episode/ feature-player/
    feature-inbox/     feature-downloads/ feature-settings/  feature-onboarding/
```

---

## 2. Backend contract (real, from `services/api`)

Base path: `/api/v1`. All podcast/discovery endpoints are **unauthenticated**;
sync/account endpoints require a **device Bearer token** (native clients do NOT use
cookies).

### 2.1 Server selection (MUST be first-run + in settings)
Self-hosters run their own server. The app must let the user choose the server:
- Onboarding step + Settings entry: **"KoalaCast server"** with a text field
  (default: the official instance URL — TBD; make it a `BuildConfig`/DataStore value).
- Validate by calling `GET {base}/api/v1/healthz` (and `/readyz`) before saving.
- Store in **DataStore**; all Retrofit calls use it as the base URL.
- Support switching servers (warn that account/sync state is per-server).
- Handle plain-HTTP self-host instances (allow user opt-in to cleartext for LAN).

### 2.2 Endpoints (from `routes.go`)
| Method | Path | Auth | Purpose |
| :-- | :-- | :-- | :-- |
| GET | `/healthz`, `/readyz` | – | Probes (validate server URL). |
| GET | `/podcasts/discover` | – | Trending / featured shows. |
| GET | `/podcasts/search?q=` | – | Search (backed by Podcast Index). |
| POST | `/podcasts/feed` | – (rate-limited 20/min/IP) | Resolve/ingest a feed URL → canonical podcast id. |
| GET | `/podcasts/{id}` | – | Podcast metadata. |
| GET | `/podcasts/{id}/episodes` | – | Episode list. |
| GET | `/episodes/{id}` | – | Episode detail (show notes are attacker-controlled HTML — sanitize before render). |
| POST | `/auth/device/login` | – (rate-limited) | **Native login → returns revocable `device_token`.** |
| POST | `/auth/register`, `/auth/login`, `/auth/recovery/verify` | – | Account lifecycle. |
| GET | `/auth/me` | Bearer | Current user. |
| POST | `/auth/logout` | Bearer | Revoke current device session. |
| GET/DELETE | `/auth/sessions`, `/auth/sessions/{id}` | Bearer | List / revoke device sessions. |
| GET | `/sync` | Bearer | **Pull** changes (subscriptions, queue, favorites, progress). |
| POST | `/sync` | Bearer | **Push** local changes. |
| POST | `/sync/merge` | Bearer | Merge local (pre-account) data on first sign-in. |
| POST/GET | `/opml/import`, `/opml/export` | Bearer | OPML. |
| … | `/admin/*` | Bearer + admin | Not needed for the client. |

### 2.3 Auth / token flow (native)
1. `POST /api/v1/auth/device/login` with device name + client type `android`.
2. Server returns a `device_token` (row in `device_credentials`, revocable, expiring).
3. Send `Authorization: Bearer <device_token>` on all authed calls (OkHttp interceptor).
4. Store token in **EncryptedSharedPreferences / DataStore + Keystore**.
5. Surface active device sessions in Settings (list/revoke) via `/auth/sessions`.
6. On 401 → clear token, drop to local-only mode, prompt re-auth (never lose local data).

### 2.4 Sync semantics
- Local-first: the app is fully usable offline/no-account. Sync is additive.
- Use the millisecond timestamps + per-session sequence fields already in the sync
  protocol for last-write-wins/merge (see `docs/sync-protocol/specification.md`).
- WorkManager periodic sync when signed in + on app foreground + after local mutation.

---

## 3. Data model (Room — mirror the web IndexedDB stores)

Web stores (`apps/web/src/lib/idb/db.ts`) to mirror as Room entities:
- `subscriptions` (podcast_id PK, feed_url, title, artwork_url, added_at)
- `playback_states` (episode_id PK, podcast_id, position_ms, completed,
  progress_percent, last_played_at, + denormalized title/podcast_title/artwork_url/
  enclosure_url/duration_ms for offline resume)
- `queue` (id PK, episode_id, podcast_id, title, artwork_url, enclosure_url,
  duration_ms, position_order, added_at)
- `favorites` (episode_id PK, added_at)
- `history` (autoincrement, played_at index)
- **New for Android:** `podcasts`, `episodes` (cached feed content for offline
  browsing), `downloads` (episode_id PK, state, progress, local_uri, size_bytes,
  downloaded_at).

Keep all time fields in **milliseconds (`Long`)** to match the server + web.

---

## 4. Must-have feature set (MVP → parity → beyond)

### 4.1 MVP (ship-blocking)
- [ ] **Onboarding + server selection** (§2.1) — pick/validate server, or "use official".
- [ ] **Discover** — trending + category chips (`/podcasts/discover`).
- [ ] **Search** — live debounced (`/podcasts/search`), add-by-RSS-URL (`/podcasts/feed`).
- [ ] **Podcast screen** — header, description, episode list, subscribe (local).
- [ ] **Episode screen** — sanitized show notes, play, add-to-queue.
- [ ] **Player** — Media3/ExoPlayer: play/pause, skip ±10/+30, scrub, speed presets
      (0.8–3.0), **sleep timer**, media notification, lockscreen, Bluetooth/media buttons.
- [ ] **Mini-player + full-screen Now Playing** (port web's expanded view + blurred
      cover backdrop + show-accent).
- [ ] **Library** — Subscriptions, **In Progress (continue listening)**, Queue, Favorites.
- [ ] **Resume playback** from saved position (works offline via denormalized metadata).
- [ ] **Local-first persistence** (Room) — everything usable with no account.
- [ ] **Theme** — System / Light / Dark (+ Material You dynamic color).

### 4.2 Feature parity with the web client
Web has these today; Android should match:
- [ ] Continue Listening rail / In-Progress tab.
- [ ] Queue (play, remove, reorder — Android should add **drag-to-reorder**, web lacks it).
- [ ] Dynamic per-show accent color from cover art (Coil palette).
- [ ] OPML import/export.
- [ ] Account: register / login / recovery code / session management.
- [ ] Sleep timer, playback-speed persistence, media session metadata.

### 4.3 New features we want (some are also web TODOs — mark them shared)
- [ ] **📥 Offline downloads** (see §5) — the headline native feature.
- [ ] **🆕 "New / Inbox" feed** — a filtered page showing **only the newest unplayed
      episodes across all subscribed podcasts**, newest first, with filters
      (unplayed / downloaded / podcast / date). **⚠️ Also required in the WEB client —
      see §8.** Needs a subscription-aware episode aggregation (client-side from
      `/podcasts/{id}/episodes`, or a future server endpoint — see §9).
- [ ] **Auto-download** newest N episodes of selected subscriptions (WorkManager, Wi-Fi-only toggle).
- [ ] **Playback tuning** — variable speed with fine steps, skip-silence / volume boost
      (Media3 audio processors), per-podcast default speed.
- [ ] **Cross-device sync** with the KoalaCast server (device token).
- [ ] **Android Auto** support (MediaLibraryService browse tree).
- [ ] **Home-screen widget** (now-playing + resume).
- [ ] **Chapters** support (from ID3/`podcast:chapters` when present).
- [ ] **Per-episode / global playback stats** (private, on-device).

---

## 5. Offline downloads (design brief — the big one)

This is the feature users expect most from a native app and the web can't do well.

Requirements:
- [ ] Download an episode's enclosure directly from the publisher URL to app storage.
- [ ] **Download queue** with states: queued / downloading / paused / done / failed,
      progress %, and byte size; drive it with **WorkManager** (survives process death).
- [ ] Downloaded episodes play from local file (ExoPlayer local `MediaItem`), fully offline.
- [ ] **Settings:** Wi-Fi-only, max concurrent downloads, storage location (internal vs
      SD/SAF), auto-delete after played, download budget / auto-cleanup by age or size.
- [ ] **Auto-download** rules per subscription (newest N, only unplayed).
- [ ] Downloads screen: list, total storage used, delete individual / all.
- [ ] Respect battery + Doze; foreground service notification while downloading.
- [ ] Handle redirects, resumable ranges, and content-length-less streams gracefully.

Recommended: **Media3 `DownloadManager` + `DownloadService`** for robustness (handles
resume, notifications, requirements/constraints), with a Room mirror for UI state.

---

## 6. Non-functional requirements
- **Privacy:** no analytics/crash SDKs that phone home by default (offer opt-in local
  logs only). No audio proxying. Minimal permissions (INTERNET, POST_NOTIFICATIONS,
  FOREGROUND_SERVICE + media; storage via SAF, no broad READ/WRITE).
- **Accessibility:** TalkBack labels on all controls, large-text support, min 48dp
  touch targets, respect "reduce motion" (system animator scale) — mirror web's
  `prefers-reduced-motion` discipline.
- **Performance:** cold start < 1s to interactive shell; lazy lists; image downsampling;
  no jank on scroll (Compose stability, `key`ed lists).
- **Battery:** ExoPlayer + WorkManager constraints; no wakelocks beyond playback/downloads.
- **Resilience:** works fully offline; graceful empty/error/loading states (skeletons).
- **i18n-ready:** all strings in resources (English first; German likely next).

---

## 7. "Wow factor" & delight (port the web polish, then go further)
- Dynamic show-accent theming (cover → accent) across headers, player, now-playing.
- Blurred cover-art ambient backdrop behind the full-screen player.
- Shared-element / container transitions between list → podcast → episode → player.
- Buttery mini-player ↔ full-player expand transition; swipe-down to collapse.
- Staggered content reveal + skeleton shimmer (match web).
- Breathing/rotating artwork while playing; live audio-reactive equalizer.
- Haptics on key actions (subscribe, add-to-queue, seek ticks).
- Material You + our forest-green identity; refined light AND dark (web's light mode
  was a known weak spot — get it right natively from day one).
- Delightful empty states with illustration + CTA (not bare text).

---

## 8. ⚠️ Shared backlog — also needed in the WEB client
Called out by the product owner: **a "newest episodes" filter page**.
- **Web (`apps/web`):** add a page/tab that aggregates the **latest unplayed episodes
  from all subscribed podcasts**, newest first, with filters. Today the web library has
  Subscriptions / In-Progress / Queue / Favorites but **no cross-subscription "new
  episodes" inbox**. Implement client-side by fetching `/podcasts/{id}/episodes` for each
  local subscription and merging by `pub_date`, or add a server endpoint (§9).
- **Android:** same feature as the **Inbox / New** screen (§4.3).
Keep the two implementations behaviorally consistent.

---

## 9. Open questions / decisions for the next agent
1. **Default server URL** — what is the official public instance? Put it in `BuildConfig`.
2. **"New episodes" data source** — client-side aggregation vs a new server endpoint
   (e.g. `GET /api/v1/episodes/new?feeds=...` or an authed `/sync`-driven inbox). A server
   endpoint scales better once a user has many subscriptions; decide before building §8.
3. **Sync granularity** — confirm exact pull/push DTOs against
   `docs/sync-protocol/specification.md` and `services/api/internal/server/handlers/sync.go`.
4. **Downloads engine** — Media3 `DownloadManager` vs custom OkHttp+WorkManager (recommend
   the former).
5. **Min SDK / target SDK** and Compose/Media3 versions — pin in the version catalog.
6. **Show-notes rendering** — HTML sanitization strategy on Android (episode content is
   attacker-controlled; the web uses DOMPurify — pick an equivalent allowlist approach).

---

## 10. Phased roadmap (suggested)
1. **P0 — Skeleton:** Gradle multi-module, Hilt, Compose nav, DataStore, **server
   selection + onboarding**, Retrofit client + healthz validation, design system + theme.
2. **P1 — Browse:** Discover, Search, Podcast, Episode screens (read-only, online).
3. **P2 — Playback:** Media3 service, mini + full player, sleep timer, speed, media session.
4. **P3 — Local-first:** Room, subscribe, queue, favorites, continue-listening, offline resume.
5. **P4 — Downloads:** download engine, downloads screen, auto-download rules.
6. **P5 — Inbox:** "New episodes" filtered feed (coordinate with web, §8).
7. **P6 — Account & Sync:** device-token auth, `/sync` pull/push/merge, session mgmt, OPML.
8. **P7 — Delight & platform:** Android Auto, widget, chapters, transitions, haptics, polish.

---

## 11. Definition of done (per feature)
- Works **offline** where applicable and **without an account**.
- Loading / empty / error states designed (skeletons, not spinners).
- Accessible (TalkBack + 48dp + reduce-motion) and themed (light + dark).
- Covered by tests (unit for logic, Compose UI for screens, MockWebServer for network).
- No new tracking, no third-party audio proxy, minimal permissions.

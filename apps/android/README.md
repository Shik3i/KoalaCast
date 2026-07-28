# KoalaCast — Native Android Client

> **Status:** ✅ **P0–P7 shipped** — browsing, playback, local-first library,
> downloads, inbox, profile statistics, account, sync, OPML, widget and platform
> features are implemented. API extensions tracked in
> [`api_todo.md`](../../api_todo.md) remain optional server-side improvements.
>
> Companion docs: [`docs/android-architecture.md`](../../docs/android-architecture.md)
> (high-level architecture), [`docs/sync-protocol/specification.md`](../../docs/sync-protocol/specification.md)
> (sync DTOs), [`apps/web/`](../web) (reference implementation — feature parity target).

---

## Building it right now

Requires JDK 17 and an Android SDK with platform 36 / build-tools 36. Point
`local.properties` (`sdk.dir=…`) or `ANDROID_HOME` at the SDK.

```bash
cd apps/android && ./gradlew assembleDebug
```

```bash
cd apps/android && ./gradlew build
```

`build` runs the unit tests and Android Lint across every module and is the same
gate used by Android CI.

### Release signing and recovery

The permanent Android release key has two protected copies:

- GitHub Actions secrets build tagged releases.
- [`android-signing.env.enc`](../../android-signing.env.enc) is the SOPS/age
  disaster-recovery copy. Both registered development machines can decrypt it
  with their existing private age identities.

Private age identities, decrypted environment files and raw
`*.keystore`/`*.jks` files never belong in Git. Validate the tracked backup with:

```bash
sops filestatus --input-type dotenv android-signing.env.enc
sops decrypt --input-type dotenv --output-type dotenv android-signing.env.enc >/dev/null
```

When adding or replacing a public age recipient, update
[`.sops.yaml`](../../.sops.yaml) and rewrap without exposing plaintext:

```bash
sops updatekeys --input-type dotenv -y android-signing.env.enc
```

The debug build installs alongside a release build (`applicationId` suffix `.debug`).
On first run the app asks which KoalaCast server to talk to and defaults to
`https://cast.koalastuff.net`; the emulator shortcut fills in `http://10.0.2.2:3000`
for a server running on the host.

### What is built

| Module | Contents |
| :-- | :-- |
| `build-logic/` | Convention plugins (`koalacast.android.library/application/compose/hilt/feature`) so a module's build file is five lines. |
| `core:model` | Domain types and `DataResult` / `DataError`. Milliseconds everywhere. |
| `core:network` | Retrofit + kotlinx.serialization against `/api/v1`, and `HostSelectionInterceptor` — there is no compile-time base URL, every request is re-pointed at the chosen server, path prefixes included. |
| `core:data` | DataStore preferences, Room (the web's IndexedDB stores mirrored field for field), `ServerUrl` normalisation/validation, the podcast / library / queue / progress repositories, `ArtworkUrls` (image-proxy routing). |
| `core:player` | `MediaLibraryService` + ExoPlayer (browse tree for Android Auto / Wear), the `PlayerConnection` every screen talks to, and the listening-session arithmetic behind the Profile stats. |
| `core:ui` | The **4b "Quiet Edition" design system**: all nine palettes in light and dark (generated from the web client's stylesheet — see `apps/android/tools/generate-palettes.py`), the two bundled typefaces (Nunito, Nunito Sans), radii/spacing, and the shared components (cover with the 135° stripe placeholder, chips, segmented control, skeletons, empty/error states, sanitised show notes). |
| `feature:*` | Onboarding, Discover, Search, Podcast, Episode, Library, Inbox, Downloads, Player, Profile, Account, Global Stats and Settings. |
| `app` | Hilt entry point, Coil image loader, navigation graph, bottom bar. The bar holds four destinations — Discover, New, Library, Profile — because a fifth label truncates at the narrowest supported width. Community figures are a scope inside Profile, not a tab. |

Deliberately **not** faked:

- The session-length recommendation control (`I HAVE 25 / 40 / 60`) and mood
  tiles are not yet wired to the shipped Inbox episode corpus.
  `QueueRepository.trimTo` — the logic behind `TRIM TO 40M` — is already
  written and tested.
- The chart shows rank, cover, title and author. The momentum sparkline in the mock
  needs 7-day trend data that neither iTunes charts nor Podcast Index return — a
  drawn-from-nowhere sparkline would be exactly the kind of "stat the app cannot
  know" the handoff's copy decisions rule out.
- Silence trimming is implemented (ExoPlayer's own flag, opt-in in Settings) and
  its saved time is measured rather than estimated: the playhead outrunning
  wall-clock time, minus everything that was skipped rather than heard.
- Chapters are carried end to end: listed on the episode screen, marked on the
  player's scrubber, with previous/next chapter steps.

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
1. **True local-first + optional account-backed sync** — most FOSS players are either
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
| Audio | **AndroidX Media3** (`ExoPlayer` + `MediaLibraryService`) | Background playback, notification, lockscreen, Bluetooth/media buttons, playback resumption and an Android Auto browse tree. |
| Local DB | **Room** | Millisecond precision (`Long`). Mirrors web IndexedDB stores. |
| Preferences | **DataStore** | Server URL, theme, playback speed, and client preferences. |
| Networking | **Retrofit + OkHttp** + **kotlinx.serialization** | Talks to `/api/v1/*`. |
| Background work | **WorkManager** | Resumable download queue and constrained background work. |
| Images | **Coil** | Cover art + palette extraction for show-accent. |
| Downloads | **OkHttp + WorkManager** | Resumable offline episodes in app-private storage; see §5. |
| Testing | JUnit, Turbine (Flow), Compose UI tests, Robolectric, MockWebServer | |
| Min SDK | **26 (Android 8.0)**, target/compile **36** | Pinned in `gradle/libs.versions.toml`. |
| Build | Gradle (Kotlin DSL), version catalog (`libs.versions.toml`) | |

Module layout as built:
```
apps/android/
  build-logic/convention/ # Gradle convention plugins
  app/                    # Hilt entry point, navigation, bottom bar
  core/
    model/                # domain models, DataResult
    data/                 # repositories, DataStore   (+ Room in P3)
    network/              # Retrofit API, DTOs, host-selection interceptor
    ui/                   # design system (4b theme, components, icons, fonts)
    player/               # Media3 service + controller          — P2
    data/                 # Room, repositories, sync + WorkManager downloads
  feature/
    onboarding/ discover/ search/ podcast/ episode/ settings/
    library/ inbox/ downloads/ player/                            — P3–P5
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
| POST | `/auth/logout` | Bearer | Revokes the calling device's own token (native) / clears the web session. |
| GET | `/auth/sessions` | Bearer | Lists web sessions **and** device credentials; each item has a `kind` (`"session"` \| `"device"`) and `is_current`. |
| DELETE | `/auth/sessions/{id}` | Bearer | Revokes either a web session or a device token by id (user-scoped). |
| GET | `/sync` | Bearer | **Pull** changes (subscriptions, favorites, playback state, listening sessions). |
| POST | `/sync` | Bearer | **Push** local changes. |
| POST | `/sync/merge` | Bearer | Merge local (pre-account) data on first sign-in. |
| POST/GET | `/opml/import`, `/opml/export` | Bearer | OPML. |
| … | `/admin/*` | Bearer + admin | Not needed for the client. |

### 2.3 Auth / token flow (native)
1. `POST /api/v1/auth/device/login` with device name + client type `android`.
2. Server returns a `device_token` (row in `device_credentials`, revocable, expiring).
3. Send `Authorization: Bearer <device_token>` on all authed calls (OkHttp interceptor).
4. Store token in **EncryptedSharedPreferences / DataStore + Keystore**.
5. Surface active sessions in Settings via `GET /auth/sessions` (filter/label by `kind`);
   revoke any with `DELETE /auth/sessions/{id}`; sign out with `POST /auth/logout` (revokes
   this device's token server-side). Device tokens currently expire after 90 days — no refresh
   endpoint yet, so plan for a re-login prompt on expiry.
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
- [x] **Onboarding + server selection** (§2.1) — pick/validate server, or "use official".
- [x] **Discover** — cover story, category chips, chart (`/podcasts/discover`).
- [x] **Search** — live debounced (`/podcasts/search`), language + genre filters,
      add-by-RSS-URL (`/podcasts/feed`).
- [x] **Podcast screen** — header, description, paged episode list, subscribe,
      per-episode play / queue / save / mark-played.
- [x] **Episode screen** — sanitized show notes, play, queue, save, mark-played.
- [x] **Player** — Media3/ExoPlayer: play/pause, skip ±15/+30, scrub, speed cycling,
      **sleep timer** (incl. end-of-episode), media notification, lock screen,
      Bluetooth/media buttons, fine-grained speed, skip-silence and volume boost.
- [x] **Mini-player + full-screen Now Playing**, including the per-show accent
      derived from cached cover art.
- [x] **Library** — Subscriptions, **In Progress (continue listening)**, Queue, Favourites.
- [x] **Resume playback** from saved position (works offline via denormalised metadata).
- [x] **Local-first persistence** (Room) — everything usable with no account.
- [x] **Theme** — System / Light / Dark across all nine palettes, matching the web
      client one for one. The colour values are generated from
      `apps/web/src/lib/styles/app.css` (`make android-palettes`) so the two clients
      cannot drift apart. *Material You dynamic color is intentionally not wired: the
      palettes are contrast-tested pairs, and recolouring them from the wallpaper
      would undo that work.*

## Before tagging a release

Run `make android-release-check` from the repository root. It runs exactly what
`.github/workflows/android-release.yml` runs — `./gradlew --no-daemon test lint
assembleRelease` — and `lint` is the part that is easy to skip locally: a build
and the unit tests can both pass while lint fails the release.

### 4.2 Feature parity with the web client
Web has these today; Android should match:
- [x] Continue Listening rail / In-Progress tab.
- [x] Nine colour palettes in light and dark, with Fjord as the shared default.
- [x] Queue (play, remove, accessible up/down reorder; drag remains optional polish).
- [x] Dynamic per-show accent color from cover art (Coil + Palette).
- [x] OPML import/export.
- [x] Account: register / login / recovery code / session management.
- [x] Sleep timer, playback-speed persistence, media session metadata.

### 4.3 Native features and remaining platform work
- [x] **📥 Offline downloads** (see §5) — resumable, process-safe internal/external/SAF downloads.
- [x] **🆕 "New / Inbox" feed** — a filtered page showing **only the newest unplayed
      episodes across all subscribed podcasts**, newest first, with an unplayed toggle
      and per-podcast `all` / `latest only` inclusion, plus downloaded, podcast, date,
      mood and 25/40/60-minute session filters. It currently aggregates
      `/podcasts/{id}/episodes` client-side; a batch server endpoint remains in
      `api_todo.md`.
- [x] **Auto-download** newest N unplayed episodes of selected subscriptions (WorkManager, Wi-Fi-only toggle).
- [x] **Playback tuning** — variable speed with fine steps, skip-silence / volume boost
      (Media3 audio processors), per-podcast default speed.
- [x] **Cross-device sync** with the KoalaCast server (device token).
- [x] **Android Auto** support (MediaLibraryService browse tree).
- [x] **Home-screen widget** (now-playing + resume/play-pause).
- [x] **Chapters** support (`podcast:chapters`, episode list and player navigation).
- [x] **Per-episode / global playback stats** (private, on-device).

---

## 5. Offline downloads (design brief — the big one)

This is the feature users expect most from a native app and the web can't do well.

Requirements:
- [x] Download an episode's enclosure directly from the publisher URL to app storage.
- [x] **Download queue** with states: queued / downloading / paused / done / failed,
      progress %, and byte size; drive it with **WorkManager** (survives process death).
- [x] Downloaded episodes play from local file (ExoPlayer local `MediaItem`), fully offline.
- [x] **Settings:** Wi-Fi-only, newest-N count and automatic retention after played/by age.
- [x] **Settings:** configurable concurrent-download limit, storage location (internal vs
      SD/SAF), and download budget / cleanup by total size.
- [x] **Auto-download** rules per subscription (newest N, only unplayed).
- [x] Downloads screen: list, total storage used, delete individual / all.
- [x] Respect battery + Doze; foreground service notification while downloading.
- [x] Handle redirects, resumable ranges, and content-length-less streams gracefully.

Implemented with **OkHttp + WorkManager**, resumable range requests,
internal/external app storage or SAF, budget cleanup, and a Room mirror for UI state.

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
- **Internationalization:** all strings are resources; English and German ship.

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

## 8. Shared web/Android Inbox

Both clients ship a cross-subscription **Inbox / New** view. They aggregate
episodes client-side by fetching each subscribed podcast and merging by
publication date. The remaining shared backend improvement is the batched Inbox
endpoint specified in [`api_todo.md`](../../api_todo.md), which will remove the
per-subscription request fan-out.

---

## 9. Decisions taken (was: open questions)
1. **Default server URL** — `https://cast.koalastuff.net`, as
   `KoalaCastDefaults.SERVER_URL` in `core:data`. It is only a *default*: onboarding
   and Settings both let a listener point the app anywhere, and the URL is validated
   against `/api/v1/healthz` before it is stored. A plain-HTTP address is allowed
   (LAN self-hosting) but always warned about.
2. **"New episodes" data source** — client-side fan-out is implemented; a batch
   endpoint remains in `api_todo.md`.
3. **Sync granularity** — subscriptions, favorites, playback state and listening
   sessions are materialized by the server. Queue and show-settings
   materialization plus lossless pagination/snapshot remain backend work in
   `api_todo.md`.
4. **Downloads engine** — implemented with OkHttp + WorkManager, resumable range
   requests and app-private storage.
5. **Min / target SDK** — **26 / 36**, `compileSdk 36`, pinned in
   `gradle/libs.versions.toml` together with AGP 8.13.2 and Kotlin 2.2.21. Hilt is
   held at 2.57.2 because 2.58+ requires AGP 9.
6. **Show-notes rendering** — `HtmlSanitizer` + Compose's HTML-to-`AnnotatedString`
   conversion (`core:ui/component/ShowNotes.kt`). Script/style/iframe/object/embed
   blocks are dropped with their content, inline `on*` handlers are stripped, and only
   `http`/`https`/`mailto` links are ever followed. No WebView is involved. Covered by
   `HtmlSanitizerTest`.

### Still worth knowing
- **Artwork is proxied through the listener's own server by default**
  (`/api/v1/proxy/image`), so browsing does not leak the device's IP to publisher CDNs
  or Apple. Switchable in Settings. Audio is never proxied.
- **Fonts and icons are bundled**, not fetched: Archivo / Bricolage Grotesque / Outfit /
  IBM Plex Mono (OFL) and the Phosphor glyphs actually used (MIT, as path data in
  `core:ui/icon/PhosphorIcons.kt`). The app makes no third-party request at launch.
- **ViewModel-level tests are the next testing gap.** Repository, interceptor, URL and
  sanitizer logic are covered; the ViewModels are not, because the repositories are
  concrete classes. Introduce interfaces (or a test module) when P2 starts.

---

## 10. Phased roadmap
1. ✅ **P0 — Skeleton:** Gradle multi-module + convention plugins, Hilt, Compose nav,
   DataStore, **server selection + onboarding**, Retrofit client with a runtime-
   switchable base URL and healthz validation, design system + theme.
2. ✅ **P1 — Browse:** Discover, Search, Podcast, Episode screens (read-only, online).
3. ✅ **P2 — Playback:** Media3 `MediaSessionService`, mini + full player, sleep timer, speed, media session, progress persistence, queue auto-advance.
4. ✅ **P3 — Local-first:** Room, subscribe, queue, favourites, continue-listening, offline resume, tombstones for sync.
5. ✅ **P4 — Downloads:** resumable download engine, downloads screen, offline playback and auto-download rules.
6. ✅ **P5 — Inbox:** "New episodes" filtered feed.
7. ✅ **P6 — Account & Sync:** device-token auth, `/sync` pull/push/merge, session mgmt, OPML.
8. ✅ **P7 — Delight & platform:** widget, dynamic cover palette, richer Inbox
   recommendations and advanced storage management.

---

## 11. Definition of done (per feature)
- Works **offline** where applicable and **without an account**.
- Loading / empty / error states designed (skeletons, not spinners).
- Accessible (TalkBack + 48dp + reduce-motion) and themed (light + dark).
- Covered by repository/logic unit tests, MockWebServer integration tests and
  Compose UI regression tests. Instrumented execution requires an attached device.
- No new tracking, no third-party audio proxy, minimal permissions.

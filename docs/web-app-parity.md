# Web ↔ Android parity

Where the mobile web client and the Android app deliberately differ, and what is
still outstanding. The goal is that the web client at phone width reads as the
same product as the app — same running order, same controls, same weight — not
that the two are pixel-identical.

Screens were compared at **320 × 640 CSS px**, which is the emulator's viewport
(320 dp at density 160), so the two sets of screenshots line up one to one.

## Conventions the two clients now share

| Element | Shared shape |
| :--- | :--- |
| Bottom navigation | Four tabs — Discover, New, Library, Profile — with Phosphor `compass` / `tray` / `books` / `user-circle`, filled when active |
| Segmented control | Bordered host on the sunken ground, equal-width text-only segments, active segment filled with the accent |
| Empty state | 56px circular accent badge around the icon, one line of explanation, one outlined action, no card |
| Buttons | One `.btn` definition; 44px minimum height, `--radius-control` |
| Control radii | `--radius-control` (5px) for anything pressable, `--radius-inset` (3px) for a segment inside a segmented host |
| Icon buttons (Android) | `KoalaIconButton.row` 30/16dp, `KoalaIconButton.compact` 26/14dp — those two and nothing else |

## Deliberate deviations

These are intentional. Each is a feature that exists on one client and not the
other, or a platform affordance that would be worse if copied literally.

### 1. Session length and mood filters — web only

The web Discover screen has an "I have 25 / 40 / 60 min" control and a grid of
mood cards. The app has neither.

**Resolution:** kept, but moved *below* the chart list on phones, so the first
screenful matches the app (search → cover story → genre chips → chart). They
used to sit above the cover story and pushed it off screen.

### 2. Cover story secondary actions — no overflow menu on web

The app's cover story offers `Open show`, `Latest episode` and an overflow
menu (`⋮`) holding the rest. The web client has no menu component.

**Resolution:** on phones the primary action keeps its label and the remaining
three (queue, save, hide) collapse to 44px icon-only buttons, which matches the
app's visual weight. A real overflow menu was considered and explicitly declined
— revisit if a menu component is introduced for other reasons.

### 3. Chart row actions — reduced rather than folded away

The app's chart row is `[rank] [artwork] [title/author] [⋮]`. The web row had
three 44px buttons (play, queue, hide), which on a 320px row left roughly 70px
for the title — every show read as `Globa…`.

**Resolution:** on phones only the accent play button remains in the row; queue
and hide stay available on the show's own page. Same reason as above: no menu
component. This is the deviation most worth closing once one exists.

### 4. Illustrations — web only, desktop only

The web client has drawn empty-state illustrations (`/illustrations/*.webp`).
The app uses a small circular icon badge.

**Resolution:** the illustration renders above 640px; below it the app's icon
badge takes its place. The drawing is not worth 256px of a phone screen.

### 5. OPML import shortcut — hidden on phones

The web's empty library offers `Discover podcasts` **and** `OPML import/export`.
The app offers one way out and keeps import in Settings.

**Resolution:** the OPML shortcut is hidden below 640px. It remains reachable at
`/settings#opml` on every width.

### 6. Legal footer — Profile only

Impressum / privacy / GitHub / licence used to sit under every mobile tab,
including an empty inbox. The app carries this kind of link on the profile
screen.

**Resolution:** the mobile footer renders on `/profile` only.

### 7. UI language switch — Settings only

Discover used to carry a language pill in a mobile title row. The app has no
such control on Discover; language lives in Settings, which the web client also
has.

**Resolution:** the mobile title row (title, date, language pill) is gone.

### 8. Sort tabs — web only

`For you / Charts / Length / Newest` above the chart has no app equivalent.
Kept; it is a genuine web feature and sits where the app has nothing.

## Listening statistics

Verified against a live server, not inferred: 33 listening sessions from the
Android client had reached `listening_sessions`, `/api/v1/stats/global` returned
them correctly, and the app's own **You** and **Community** scopes both showed
1 h 21 min — the same figure. Session sync itself is sound.

Two faults were found around it.

**Opting out of global statistics was impossible.** `GlobalStatsPreference` is a
kotlinx-serialization class with `enabled: Boolean = false`, and the encoder
omits a property equal to its default. Opting out therefore serialised to `{}`,
the server saw no field and answered `400`, and the preference could only ever be
turned on. Fixed with `@EncodeDefault(ALWAYS)`. Two more request fields were
silently absent for the same reason and are now sent: `client_type` on device
login and `client_schema_version` on every sync push. The server ignores the
latter today, so nothing behaved differently — but the client was claiming to
send a schema version it never sent.

**A production instance had zero sessions while reporting one participant —
R8 was the cause.** `GET https://cast.koalastuff.net/api/v1/stats/global`
returned `participants: 1, listening_sessions: 0` for every range: the account
was opted in, the server was current, and nothing had arrived to aggregate.

The client was failing every sync with
`IllegalArgumentException: Unable to create converter for class java.lang.Object`
— and only in a minified build. R8's full mode, the default since AGP 8,
discards generic signatures nothing demonstrably reads. Retrofit reads them
reflectively when it builds a service method, so an erased `Continuation` type
made every `suspend fun … : Response<Dto>` resolve to `java.lang.Object`.
`app/proguard-rules.pro` was empty on the assumption that library consumer rules
covered it; they do not cover full mode. Reproduced by building the release APK
twice with `validateEagerly(true)` — the error appears without the rules and is
absent with them.

Two things kept this hidden for so long, and both are fixed:

- `syncNow` caught every failure with a bare `catch (_: Exception)` and set
  `SyncStatus.ERROR` with no reason kept anywhere, so a sync that never
  succeeded was undiagnosable from the screen, a log or a bug report. The reason
  and its cause are now retained, logged under `KoalaCastSync`, and shown on the
  Account screen untranslated and wrapped.
- Debug builds are not minified, so the fault could not occur in development or
  on an emulator — only in the signed release people actually install.

With the converter fixed the push finally ran, and the server rejected it with
`400`. The client reported only the status code and aborted the whole sync, so
the watermark never moved and the next attempt resent the same operation — one
unacceptable record could keep an entire account's data off the server for good.
A batch that is rejected is now halved and retried until the offending operation
is alone; that one is reported with the server's own explanation and skipped, and
everything else goes through. Only 400 is treated this way: a 500 or a 429 is
about the request as a whole and still fails the sync so it retries.

Also fixed while here: the listening-session push watermark advanced to
wall-clock time captured before the outgoing operations were built. Sessions are
written asynchronously when playback stops, so one landing just after that query
had an `endedAt` below the new watermark and was skipped permanently. It now
advances only as far as the newest session actually sent.

**Category breakdowns are empty, and cannot be fixed on the client.** Every
session the Android app writes carries `categories: []`, so the global category
chart has exactly one bar, labelled "Uncategorised". The chain is missing end to
end: the `podcasts` table has no category column, `/api/v1/podcasts/{id}` returns
none, and the `Podcast` model — unlike `PodcastSummary`, which discovery and
search return — has no `categories` field to read. Closing this needs a schema
migration, category ingestion from the RSS feed, an API field and then the client
plumbing. It is a feature, not a bug fix, and was deliberately left alone.

## Still outstanding

Not yet reconciled. Roughly in order of how visible they are.

- **Overflow menus.** Items 2 and 3 above both wait on a shared menu component
  for the web client. Until then the two clients offer the same actions in
  different places on a phone.
- **Podcast and episode screens** were not compared screen by screen; only the
  four tab roots and the player were. Expect drift there.
- **Search screen** has its own empty state that still lays out horizontally
  above 640px; only the shared vertical treatment was applied.
- **Statistics cards.** The app lays out a two-column grid of stat tiles; the
  web profile uses a different card set below the range control. Structure was
  not unified, only the header and the controls above it.
- **Theme parity.** Both clients ship the same nine palettes, but the app was
  compared in light mode and the web in dark. Colour tokens are shared by name,
  not verified side by side per palette.

## How to re-run the comparison

The app screenshots come from a running emulator:

```bash
adb exec-out screencap -p > app-discover.png
```

The web screenshots come from Playwright at the same viewport. There is no
committed script for this — it was a throwaway harness — but the settings that
matter are `viewport: { width: 320, height: 640 }`, `deviceScaleFactor: 1` and
`isMobile: true`.

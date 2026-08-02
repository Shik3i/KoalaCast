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

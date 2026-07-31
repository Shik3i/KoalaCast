# Audio visualiser roadmap

**Status:** proposed, not started.

Palette-aware audio visualisers on the Android player, chosen in Settings next to
the colour palette. A visualiser either rides on the progress bar or replaces it,
depending on the preset.

---

## Product contract

1. **No new permissions.** Not one. The visualiser is decoration; it may not cost
   the listener anything they would have to think about.
2. **Never the source of truth for position.** Whatever the visualiser draws, the
   listener can still see and set where they are in the episode. Presets that
   replace the progress bar must still be a progress bar.
3. **Off is a first-class choice, and it is the default.** An existing listener
   who updates the app sees exactly what they saw before.
4. **Palette-correct by construction.** A preset consumes design tokens only. It
   is never allowed to name a colour.
5. **Silence looks like silence.** If the visualiser cannot see real audio (remote
   playback, an unexpected sink), it degrades to the plain bar rather than
   inventing motion.

---

## Why not the obvious API

`android.media.audiofx.Visualizer` is the API every tutorial reaches for, and it
requires `android.permission.RECORD_AUDIO`.

That is disqualifying here, not merely inconvenient. Onboarding tells the listener
that KoalaCast keeps their listening on the device; the Play Store data-safety
form would then have to declare microphone access; and a large share of listeners
would decline the prompt and get a broken feature. A decoration is not worth any
of that.

Note the asymmetry that makes this easy to get wrong: `LoudnessEnhancer`, which
the service already attaches to the audio session for volume boost
(`PlaybackService.attachLoudnessEnhancer`), needs no such permission. "We already
use `audiofx`" does not imply `Visualizer` is free.

---

## The approach

A custom `androidx.media3.common.audio.AudioProcessor` inserted into the
ExoPlayer audio pipeline. It sees the app's own decoded PCM, so there is nothing
to ask permission for.

### What makes this cheap here

`PlaybackService` declares no `android:process` in the manifest, so it runs in the
**same process as the UI**. The processor can publish into a singleton
`StateFlow` that Compose collects directly.

This removes the part that is normally the expensive half of the feature: no
`MediaSession` custom events at frame rate, no `Bundle` marshalling, no IPC
back-pressure design. If the service is ever moved to its own process, this
roadmap needs rewriting before anything else.

### The one real risk

`ExoPlayer.Builder(this)` is currently constructed without a custom
`RenderersFactory` (`PlaybackService.onCreate`), and two shipped features live in
the default audio pipeline:

- `skipSilenceEnabled` — `SilenceSkippingAudioProcessor`
- variable playback speed — `SonicAudioProcessor`

Replacing the processor chain naively breaks both. The documented path is
`DefaultAudioSink.DefaultAudioProcessorChain(vararg custom)`, which applies
custom processors *before* silence skipping and speed adjustment. That should
preserve both, but it is the single assumption in this document that has not
been verified against a running player, and everything else depends on it.

**Verify it first.** See "Phase 0".

Two ordering questions to settle while verifying, because they change what the
listener sees:

- Placing the tap *before* silence skipping means the visualiser shows the audio
  as published, including gaps the player is about to remove — motion during
  silence that never reaches the speaker.
- Placing it *after* Sonic means the amplitude envelope is time-compressed at
  1.5×, which is what the listener is actually hearing.

The second is almost certainly right. The API makes the first easier. Resolve
this with ears, not reasoning.

---

## Signal design

Per processed buffer, compute one `Float` — RMS over the frame, normalised and
smoothed — and push it into a fixed-size ring buffer. No FFT.

Constraints on the processor, which runs on the audio thread:

- **Zero allocation per buffer.** A pre-allocated `FloatArray` ring, primitive
  arithmetic, no boxing, no lambdas capturing.
- **Never blocks.** No locks, no channel that can suspend. A single writer and a
  volatile write index; a torn read shows one stale frame, which is invisible at
  60 Hz and infinitely better than a glitch in the audio.
- **Cheap when nobody is looking.** The processor stays in the chain but
  short-circuits to a passthrough when the preset is Off or no visualiser is
  subscribed. It must not cost battery for the listeners who never enable it.

The UI samples this ring on the Compose frame clock, not on a timer of its own,
and only while the player is resumed.

### Deliberately excluded: FFT

A spectrum needs a radix-2 FFT (nothing suitable is in the version catalogue, so
it would be hand-written or a new dependency) and costs 1–2 days. For speech it
looks busier without saying more than the amplitude envelope does. Revisit only
if a preset genuinely needs frequency content — a spectrum-shaped preset can be
faked convincingly from a single amplitude value plus per-band phase offsets.

---

## Where it attaches in the UI

Both insertion points already exist and need no restructuring:

- **Full player** — `NowPlayingSheet.Scrubber` overrides Material's `Slider`
  `track` slot. That slot is the hook; the thumb, the seek gesture, the chapter
  markers and the time codes all stay exactly as they are.
- **Mini player and library rows** — `core/ui/component/ProgressTrack.kt`.

The mini player deliberately stays plain. It is on screen almost always, and an
animation there is a battery cost paid during every episode. Visualisers run on
the full player only, and only while it is resumed.

### Palette

Nothing new is required. `KoalaTheme.colors` already carries `accentFill`,
`accentInk`, `track`, `dataBar` and `borderUi`, generated from the web client's
CSS (`make android-palettes`). A preset that uses only those tokens is correct in
all nine palettes and in both light and dark, for free.

The design system's own constraint applies: "No shadows anywhere: depth comes
from surface value plus hairlines." Glow-heavy presets fight that. The existing
`spotlightGlow` modifier is the sanctioned exception and is the only glow
primitive presets may use.

---

## Presets

| Preset | Relationship to the bar | Signal |
| --- | --- | --- |
| **Off** | is the bar | none |
| **Level** | the fill breathes with amplitude | RMS, heavy smoothing |
| **Waveform** | RMS history behind the played portion | ring buffer |
| **Bars** | replaces the bar | RMS + per-band phase offsets |
| **Blade** | replaces the bar | RMS drives flicker; length is progress |

**Blade** is the "energy sword" idea: the played portion is a lit blade, the
playhead is its tip, the unplayed portion is the hilt track, and amplitude
modulates a subtle flicker along the edge.

Ship it under a generic name. The visual is unproblematic; "Lightsaber" is a
Lucasfilm trademark and does not belong in a released product's settings screen.
"Blade" or "Plasma" carries the same idea with none of the exposure.

---

## Settings

A `Visualiser` section immediately after `Color palette` in `SettingsScreen`,
following the palette picker's pattern: each row renders **itself** in its own
preset, animating against a canned amplitude loop, so the choice is made by
looking rather than by reading a name.

New preference `visualizer`, plumbed exactly like `startScreen` was:

- `core/model/Preferences.kt` — enum with a stable `id`, plus `DEFAULT = OFF`
- `PreferencesRepository` — scoped key, setter, `applySynced`, `resetSynced`,
  `migrateGuestToAccount`, `migrateUserScope`
- `SyncRepository` — `visualizer` in `settingsPayload` and `applySettings`
- `SyncedSettings.ownedKeys` — and its test's expected set

No server change: the `settings` entity payload is stored opaquely
(`services/api/internal/server/handlers/sync.go`).

One invariant to respect: `visualizer` must be added to `SyncedSettings.ownedKeys`
in the same change that adds it to the payload, or this client stores its own key
as foreign and writes it twice. See the settings-sync note in
[roadmap.md](../roadmap.md) for why that machinery exists.

---

## Delivery sequence

**Phase 0 — de-risk the pipeline (~1 day).**
A custom `AudioProcessor` that only counts buffers, wired through
`DefaultAudioProcessorChain`, plus a throwaway numeric readout. Success is not
"it draws something" — it is: skip-silence still skips, speed still changes
pitch-corrected, volume boost still boosts, downloads still play, gapless
transitions still work. If this phase fails, the whole approach is wrong and
nothing below is worth planning.

**Phase 1 — signal (~1 day).**
RMS, ring buffer, `StateFlow`, subscription gating. Verified with a temporary
debug readout, not a pretty preset.

**Phase 2 — plumbing (~½ day).**
Preference, sync, Settings section, live previews. Mechanically identical to the
start-screen preference; the shape is known.

**Phase 3 — presets (~2–4 h each).**
Level first — it is the most conservative and validates that a visualiser can
live inside the `Slider` track slot without disturbing seeking. Then Waveform,
Bars, Blade.

**Phase 4 — hardening (~½ day).**
Reduced-motion, remote-playback fallback, battery measurement, `clearAndSetSemantics`
audit.

Roughly **3–5 days** for real audio plus four presets. A decoration-only version
driven by position and `isPlaying` is about a day, but it visibly lies during
speech pauses — which is exactly when a listener looks at it.

---

## Hardening details

- **Reduced motion.** Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` by
  falling back to the static bar. A visualiser is motion for its own sake, which
  is the category that setting exists for.
- **Accessibility.** `ProgressTrack` is `clearAndSetSemantics { }` on purpose —
  the same number is always available as text. Presets must stay equally silent;
  TalkBack must never describe bars.
- **Remote playback.** No local PCM means no signal. Fall back to the plain bar.
  Not reachable on Android today, but the web client already has a remote
  playback picker, so plan for it rather than discover it.
- **Battery.** Measure before and after on a real device with the screen on and
  the player open. If a preset costs meaningfully more than the static bar, that
  is a bug in the preset, not a cost to accept.

---

## Explicit non-goals

- Microphone or system-output capture, in any form, for any preset.
- Visualisers in the mini player, notification, widget, or Android Auto.
- Per-podcast visualiser overrides. This is a theme choice, not a playback
  setting.
- Web parity in the same change. The web client can do this far more cheaply with
  a WebAudio `AnalyserNode`; if it follows, preset names and token mapping should
  be shared the way the palettes already are, rather than reinvented.

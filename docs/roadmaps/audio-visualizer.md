# Audio visualiser status

**Status:** shipped on Android: Off, Level, Waveform, Bars and Pulse.

Palette-aware visualisers are selected in Settings and rendered in the full
player. Off is the default. The feature reads the app's decoded PCM through a
Media3 `TeeAudioProcessor`; it does not use
`android.media.audiofx.Visualizer` and requires no microphone permission.

## Product contract

1. The visualiser never changes or becomes the source of playback position.
2. Off remains a first-class, zero-animation default.
3. Presets consume KoalaCast palette tokens only.
4. Missing PCM degrades to the ordinary progress bar; motion is never invented.
5. The mini player, notification, widget and Android Auto remain static.

## Implementation

- `core:player/AmplitudeTap` computes a smoothed amplitude and fixed-size
  spectrum history without per-buffer allocation or blocking the audio thread.
- `core:ui/component/Visualizer.kt` renders Level, Waveform, Bars and Pulse.
- `feature:player/NowPlayingSheet` keeps the slider thumb, seek gesture, chapter
  markers and time labels above the visual treatment.
- `VisualizerStyle` is a stable synced preference. Unknown IDs fall back to
  Off; the retired `dots` ID migrates to Bars.
- Reduced-motion mode falls back to the plain bar, and decorative geometry is
  hidden from TalkBack.

The custom processor stays before Media3's speed and silence-skipping stages.
Device measurements confirmed both features remain active with the tap installed.

## Remaining hardening

- Measure battery and CPU cost on a representative physical device with each
  preset and with Off.
- Tune `GAIN`, `ATTACK` and `RELEASE` against a broader speech/music sample.
- Re-run the plain-bar fallback on every new remote-output path, because remote
  playback may not expose local PCM.

These are verification/tuning tasks, not unimplemented presets.

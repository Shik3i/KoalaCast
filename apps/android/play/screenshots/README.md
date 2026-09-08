# Phone screenshots

1080×1920 — the resolution and 9:16 ratio Play asks for if the listing is to be
eligible for promotion. Play accepts anything from 320 px to 3840 px per side,
but the 320×640 viewport used for the parity comparison in
`docs/web-app-parity.md` is far below what a store listing should show.

Eight is Play's maximum for a phone, and the set spends them deliberately: the
first three are the light theme, the rest are dark, and two of them are the
player itself, because the visualizer is what 0.11.8 is about.

| File | Screen | Theme |
| :--- | :--- | :--- |
| `01-discover.png` | Discover — cover story, genre filter, charts | Light |
| `02-show.png` | Subscribed show and episode controls | Light |
| `03-player-visualizer.png` | Player with the Bars visualizer | Light |
| `04-player-dark.png` | Player with the Spectrum visualizer | Dark |
| `05-visualizer-styles.png` | Choosing a visualizer, each style previewed live | Dark |
| `06-palettes.png` | Theme switch and the nine colour palettes | Dark |
| `07-library.png` | Library | Dark |
| `08-new.png` | New — what the subscriptions published | Dark |

## Retaking them

The emulator's own panel is the geometry; do **not** force a size larger than
the physical display. Overriding a 320×640 panel up to 1080×1920 makes Android
render large, SurfaceFlinger scale down to the real panel and the capture scale
back up, and the result is visibly soft text. The `koala36` AVD is configured
with a real 1080×2400 panel at 420 dpi, so the override below only shortens the
viewport and every pixel stays 1:1.

```bash
adb shell wm size 1080x1920 && adb shell wm density 440
```

Play wants the status bar to look finished — full battery, full signal, no
carrier label — so drive System UI demo mode before each capture:

```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1041
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
```

`-e fully true` is what removes the "connected without internet" exclamation
mark from the Wi-Fi glyph, and hiding mobile removes the `3G` label; both
appeared in the first attempt at this set.

Then capture and reset:

```bash
adb shell screencap -p /sdcard/s.png
adb pull /sdcard/s.png apps/android/play/screenshots/01-discover.png
adb shell wm size reset && adb shell wm density reset
adb shell cmd uimode night no
```

Light and dark come from `adb shell cmd uimode night no|yes`, because the app's
theme setting defaults to following the system.

## Rules the set has to keep

- **Never an empty state.** The first attempt at Library and New was captured on
  a fresh install and showed "No shows yet", which is an advertisement for an
  empty app. Subscribe to two or three shows first.
- **English.** The emulator here runs in German; the app is pinned per-app with
  `adb shell cmd locale set-app-locales net.koalastuff.koalacast.debug --locales en-US`,
  which needs no reboot on Android 13+.
- Re-capture whenever the visible UI changes. Do not upload stale screenshots
  from an older release.

The committed set was captured on 2026-09-08 from `0.11.8 (48)`. It uses the
debug build, which differs from release only in its application id and signing —
none of it visible on these screens; a locally built release APK is not possible
because signing happens in CI.

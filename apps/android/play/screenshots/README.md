# Phone screenshots

1080×1920, captured from the `koala36` emulator forced to a real phone geometry.
Play accepts anything from 320 px to 3840 px per side, but the 320×640 viewport
used for the parity comparison in `docs/web-app-parity.md` is far below what a
store listing should show.

| File | Screen |
| :--- | :--- |
| `01-discover.png` | Discover — cover story, genre filter, charts |
| `02-show.png` | Subscribed show and episode controls |
| `03-new.png` | New — what the subscriptions published |
| `04-library.png` | Library |
| `05-profile.png` | Listening profile |

## Retaking them

```bash
adb shell wm size 1080x1920 && adb shell wm density 440
adb shell am force-stop net.koalastuff.koalacast
adb shell am start -n net.koalastuff.koalacast/.MainActivity
adb exec-out screencap -p > apps/android/play/screenshots/01-discover.png
adb shell wm size reset && adb shell wm density reset
```

The committed set uses a real subscription and System UI demo mode so the status
bar is stable and contains no emulator-only warning icons.

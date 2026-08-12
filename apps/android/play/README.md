# Play Console submission material

Everything the Play Console asks for that is worth keeping under review, kept
next to the code rather than only inside the Console. Nothing here is read by
the build — it is copy for humans to paste, and a record of what was declared,
so the next submission does not have to reconstruct it from memory.

| File | What it is for |
| :--- | :--- |
| [`listing-en.md`](listing-en.md) | Store listing, English |
| [`listing-de.md`](listing-de.md) | Store listing, German |
| [`play-store-source-en-US.txt`](play-store-source-en-US.txt) | Upload-ready English source for the Play Console AI localization import |
| [`store-listing-import.csv`](store-listing-import.csv) | Structured EN/DE reference and translation context |
| [`data-safety.md`](data-safety.md) | Answers for the Data safety form |
| [`declarations.md`](declarations.md) | App access, foreground service, permissions, account deletion |
| [`release-notes-v0.11.0.md`](release-notes-v0.11.0.md) | Play release notes, English and German |
| [`feature-graphic.png`](feature-graphic.png) | 1024×500 feature graphic |
| [`screenshots/`](screenshots) | Phone screenshots, 1080×1920 |

## Before an internal test submission

- [ ] Create the app in the Play Console (`net.koalastuff.koalacast`)
- [ ] Accept Play App Signing; the CI keystore becomes the **upload** key
- [ ] Paste both listings, upload graphics (see below)
- [ ] Complete the Data safety form from `data-safety.md`
- [ ] Complete the content rating questionnaire (IARC)
- [ ] Complete the foreground-service declaration from `declarations.md`
- [ ] Enter the account-deletion URL from `declarations.md`
- [ ] Create the demo account and fill in App access, see `declarations.md`
- [ ] Upload the AAB from the `android-v*` release
- [ ] Add testers to the internal testing track

## Graphics

| Asset | Where it comes from |
| :--- | :--- |
| 512×512 icon | `apps/web/static/icon-512.png`, already the right size |
| 1024×500 feature graphic | `feature-graphic.png`, regenerate with the script below |
| Phone screenshots | `screenshots/`, captured from the emulator |

```bash
python apps/android/play/generate-feature-graphic.py
```

The screenshots were taken on the `koala36` emulator forced to a real phone
geometry, because the 320×640 viewport used for the parity comparison is below
what a store listing should show:

```bash
adb shell wm size 1080x1920 && adb shell wm density 440
adb exec-out screencap -p > apps/android/play/screenshots/01-discover.png
adb shell wm size reset && adb shell wm density reset
```

The committed captures use a release build with a real subscription and a fixed
demo status bar. Re-capture them whenever the visible UI changes; do not upload
stale screenshots from an older release.

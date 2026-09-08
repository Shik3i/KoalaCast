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
| [`release-notes-v0.11.8.md`](release-notes-v0.11.8.md) | Current Play release notes, English and German |
| [`feature-graphic.png`](feature-graphic.png) | 1024×500 feature graphic |
| [`screenshots/`](screenshots) | Phone screenshots, 1080×1920, eight of them |

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

The current screenshots were captured on 2026-09-08 from `0.11.8 (48)` on the
`koala36` AVD, which is configured with a real 1080×2400 panel at 420 dpi. The
full procedure — viewport, demo status bar, English locale, theme switching and
the rule against empty states — is in [`screenshots/README.md`](screenshots/README.md),
along with the mistakes that produced a soft, German, empty-looking first
attempt. Re-capture whenever the visible UI changes; do not upload stale
screenshots from an older release.

## Copy rules Play enforces

The **short description** may not carry price or promotional information. The
Console rejected "no ads or behavioral tracking" on exactly that ground, so that
field now describes features only; the absence of advertising is stated in the
full description, where the rule does not apply. The same rule bans
call-to-actions, accolades, store-performance claims and testimonials there.

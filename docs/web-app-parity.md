# Web and Android parity

**Last audited:** August 12, 2026

Both clients share the local-first data model, account/reset-generation sync,
nine palettes, explicit-content defaults, playback fundamentals and the four
primary mobile destinations. Pixel identity is not a goal; equivalent behavior,
clear platform conventions and the same privacy boundaries are.

## Deliberate differences

| Area | Web | Android | Reason |
| :--- | :--- | :--- | :--- |
| Navigation | Desktop side rails; four mobile destinations | Four bottom destinations | Platform-appropriate shells |
| Discovery controls | Additional sort modes and compact phone actions | Native cover-story and menu actions | Preserve usable phone width |
| OPML shortcut | Hidden from the phone empty state; available in Settings | Available in Settings | One primary empty-state action |
| Legal footer | Shown on Profile on mobile | Legal links in Profile/Settings | Avoid repeating legal chrome on every tab |
| Queue reorder | Drag/reorder in the full player | Accessible up/down controls in Library | Keyboard/pointer versus touch/accessibility conventions |
| Notifications | Browser Web Push plus supported background sync | Local WorkManager refresh and Android notifications | Native app does not register Web Push |
| Smart queues | Saved rules over cached episodes | Named queues only | Still a genuine web-only feature |

## Playback parity

Both clients provide live scrubbing, chapter markers and previous/next chapter
navigation, speed, skip controls, sleep timers based on listening time,
skip-silence, volume boost, queue playback and Media Session/Media3 integration.
Both expose publisher transcripts when available.

Remaining differences:

- Web keeps a reorderable queue inside the full player; Android reorders it in
  Library.
- Web transcript highlighting follows the playhead; Android presents a searchable
  transcript on the episode screen.
- Web offers a temporary “back to previous position” marker after a large seek;
  Android does not.
- Web smart queues have no Android equivalent.

## Outstanding review work

- Compare podcast and episode detail screens at matched phone widths.
- Verify all nine palettes side by side in both light and dark modes.
- Expand Android ViewModel and device-level UI coverage.
- Re-check feature claims whenever either client adds a playback, queue,
  discovery or account-data control.

## Reproducing the comparison

Capture Android from an emulator or device:

```bash
adb exec-out screencap -p > app-screen.png
```

Capture the web client at the same logical viewport with Playwright. Store
temporary comparison images outside the repository; screenshots are evidence,
not source assets.

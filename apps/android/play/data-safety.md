# Data safety form

The answers to give in the Play Console, and why. The form is a declaration —
getting it wrong is a policy violation, so each line below points at the code
that makes it true.

## Summary

| Question | Answer |
| :--- | :--- |
| Does the app collect or share any user data? | **Yes**, but only if the listener creates an account |
| Is all data encrypted in transit? | **Yes** (HTTPS; a self-hosted plain-HTTP instance is warned about in-app) |
| Can users request deletion? | **Yes** — in-app and via a URL, see `declarations.md` |
| Is any data collected required? | **No.** Everything works without an account |

## Data types to declare

Declare each as **collected**, not shared, **optional**, and for *App
functionality* only.

| Data type | When | Why |
| :--- | :--- | :--- |
| **User IDs** (username) | Only with an account | Identifies the account for sync and for the opt-in leaderboard |
| **App activity — other user-generated content** | Only with an account | Subscriptions, favorites, queue and per-show settings, so devices agree |
| **App activity — other actions** | Only with an account | Playback positions and listening sessions, so a device resumes where another left off |
| **App info and performance — other** | Only with notifications on | A Web Push endpoint, so the server can wake the device for a new episode |

Nothing else. In particular **do not** declare location, contacts, photos,
files, financial info, health, messages, or a device/advertising ID: none is
requested, and the app contains no advertising or analytics SDK.

## Answers that are easy to get wrong

**"Is data shared with third parties?" — No.** Episode audio is fetched straight
from the publisher's CDN, which means the publisher sees an IP address. That is
the ordinary consequence of playing a file from its host, not KoalaCast handing
data to anyone, and Play's definition of sharing is about transfer by the app.
Artwork is proxied through the instance by default precisely so it is *not*
disclosed; that setting is in Settings ▸ Privacy.

**"Is data processed ephemerally?" — No** for the account data above. It is
stored, which is the point of sync, and the privacy policy says so in the same
words.

**"Required or optional?" — Optional, for all of it.** Browsing, playback,
downloads, the queue and the statistics all work with no account. This is the
single most important answer on the form and the easiest to get wrong by
reflex.

## Where this is enforced in code

- No account required: `apps/android/.../onboarding`, and the local-first Room
  database in `core:data`.
- Artwork proxying: `core/data/.../server/ArtworkUrls.kt`.
- Push endpoint stored only while notifications are on, and deleted when they
  are turned off: `services/api/internal/server/handlers/push.go`.
- Deletion removes every row: `services/api/internal/server/handlers/account.go`
  with `account_test.go` asserting each user-scoped table empties.

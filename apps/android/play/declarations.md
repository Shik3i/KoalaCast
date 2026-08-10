# Console declarations

## App access ("Anmeldedaten")

The Console asks whether any part of the app is behind a login. For KoalaCast the
honest answer is *partly*:

> **Some functionality is restricted.**

Everything a reviewer needs to judge the app — discovery, search, playback,
downloads, the queue, statistics — works with no account at all, and the review
should say so. Three screens do need one: Account, cross-device sync, and the
opt-in community statistics. Provide a demo account so those can be reviewed
rather than guessed at.

Suggested instructions to paste into the Console:

```
No account is required. Discovery, search, playback, downloads, the queue and
personal statistics are fully usable on first launch — skip the onboarding
sign-in prompt with "Later".

To review the account features (Profile ▸ Account): sign in with the credentials
below. Cross-device sync starts automatically once signed in. Community
statistics are opt-in and off by default; the toggle is on the same screen.

The account can be deleted from inside the app at Profile ▸ Account ▸ Delete
account, and from https://cast.koalastuff.net/account in any browser.
```

### Creating the demo account

Do this yourself and put the credentials straight into the Console — they are a
live credential and must not be committed here.

```bash
curl -X POST https://cast.koalastuff.net/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"play-review","password":"<generate a long random password>"}'
```

The response contains a one-time recovery code. Keep it with the password in the
same place; it is the only way back into the account if the password is lost, and
it is not recoverable afterwards.

Give the account a little content before review — subscribe to two or three
shows and play a few minutes — so the sync and statistics screens are not empty
when a reviewer opens them.

## Account deletion

Play requires an app that can create an account to offer deletion **in the app**
and at a **publicly reachable URL**, without the user having to install anything.

| Field | Value |
| :--- | :--- |
| In-app path | Profile ▸ Account ▸ Delete account |
| Web URL | `https://cast.koalastuff.net/account` |
| What is deleted | The account and every row belonging to it |
| What is kept | Nothing account-scoped |

Both paths call `DELETE /api/v1/auth/account`, which requires the password or a
recovery code even though the session is already authenticated, then removes the
account row; every dependent table declares `ON DELETE CASCADE` and
`account_test.go` asserts each one empties. Sessions and device credentials are
deleted explicitly first, so a half-failed request can never leave a usable
credential behind.

Self-hosted instances answer the same URL path on their own domain.

## Foreground service types

Declared in the manifest and both need a justification in the Console.

### `mediaPlayback`

`PlaybackService` is a `MediaLibraryService`. Audio continues with the screen
off, in the background and in the car; the service owns the notification, the
lock screen, Bluetooth buttons and Android Auto. This is the ordinary,
expected use of the type — playback is the app.

### `dataSync`

`EpisodeDownloadWorker` downloads an episode for offline listening, started by
an explicit user action ("Download") or by the per-show auto-download the
listener enabled. It is a user-initiated file transfer that must survive the app
going to the background, shows an ongoing notification with progress, and stops
when the transfer finishes, fails or is cancelled. It never runs on its own
schedule and never transfers anything the listener did not ask for.

> If the Console pushes back on `dataSync`, the honest alternative is
> `androidx.work` with `setForegroundAsync` under a *user-initiated data
> transfer* job on Android 14+. The behaviour is already exactly that; only the
> declared type would change.

## Permissions

| Permission | Why |
| :--- | :--- |
| `INTERNET` | Fetch feeds, metadata and audio |
| `ACCESS_NETWORK_STATE` | Honour "download over Wi-Fi only" |
| `FOREGROUND_SERVICE` + `..._MEDIA_PLAYBACK` | Background playback |
| `FOREGROUND_SERVICE_DATA_SYNC` | Episode downloads, see above |
| `POST_NOTIFICATIONS` | Playback and download notifications; requested at runtime |
| `WAKE_LOCK` | Keep playback alive with the screen off |

No location, no contacts, no storage, no advertising ID.

## Ads and content

- **Contains ads:** No. There is no advertising SDK in the dependency graph.
- **In-app purchases:** None. There is no paid tier.
- **Target audience:** 13+ — a podcast client with third-party feeds is not
  designed for children, and the store listing must not be either.
- **User-generated content:** Episodes come from third-party RSS feeds the
  listener chooses. Nothing is published or shared between users except the
  opt-in leaderboard username.

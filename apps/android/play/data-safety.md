# Data safety form

The answers to give in the Play Console, and why. The form is a declaration —
getting it wrong is a policy violation, so each line below points at the code
that makes it true.

## Summary

| Question | Answer |
| :--- | :--- |
| Does the app collect or share any user data? | **Yes** — online requests reach the selected KoalaCast server; account sync adds the optional account data below |
| Is all data encrypted in transit? | **Yes** — the release app accepts HTTPS only; cleartext is disabled in its manifest and network-security config |
| Can users request deletion? | **Yes** — in-app and via a URL, see `declarations.md` |
| Can users delete synchronized data without deleting the account? | **Yes** — in-app and via public `/account`; execution requires sign-in plus password or recovery-code confirmation |
| Is any data collected required? | **Mixed.** Request/interaction data is required for online features; search and account data are optional |

## Data types to declare

Declare each as **collected** and not shared. Handling differs by row; select the
purposes and required/optional state listed below.

| Data type | When | Purpose | Why |
| :--- | :--- | :--- | :--- |
| **App activity — app interactions** | When an online feature is used; required for that feature | App functionality; Fraud prevention, security, and compliance | API paths and technical request details reach the chosen server and may remain in access/security logs for at most seven days |
| **App activity — in-app search history** | Only when the listener searches; optional | App functionality | Search terms are sent to the chosen server and may appear in the same maximum-seven-day technical logs |
| **User IDs** (username) | Only with an account; optional | App functionality; Account management | Identifies the account for sync and for the opt-in leaderboard |
| **Device or other IDs** (app-generated installation UUID) | Only with an account; optional | App functionality; Account management | Identifies the Android installation for device authentication, session management and sync conflict attribution |
| **App activity — other user-generated content** | Only with an account; optional | App functionality | Subscriptions, favorites, queue and per-show settings, so devices agree |
| **App activity — other actions** | Only with an account; optional | App functionality | Playback positions and listening sessions, so a device resumes where another left off |

Do not claim that account-free mode means no data leaves the device: discovery,
search, feed resolution and metadata requests still contact the selected server.
In particular **do not** declare location, contacts, photos,
files, financial info, health, messages, or an advertising ID: none is
requested, and the app contains no advertising or analytics SDK. The
app-generated installation UUID above is nevertheless a **Device or other ID**
under the Play Console definition.

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

**"Required or optional?" — answer per row.** Account/sync and search data are
optional. Network request data is inherent when a listener chooses an online
feature, even though already-downloaded and locally cached content works without
an account. Google defines collection as transmission off-device and requires
ephemeral processing to be included in the form response; seven-day technical
logging is not ephemeral.

## Where this is enforced in code

- Release transport encryption: `app/src/main/AndroidManifest.xml` sets
  `android:usesCleartextTraffic="false"`, and
  `app/src/main/res/xml/network_security_config.xml` sets
  `cleartextTrafficPermitted="false"`. The debug-only resource override permits
  HTTP solely for `localhost`, `127.0.0.1`, and `10.0.2.2`; it is absent from the
  release APK/AAB. `ServerUrl` rejects disallowed origins before validation and the
  central OkHttp `TransportSecurityInterceptor` rejects a disallowed final URL,
  including redirects.
- No account required: `apps/android/.../onboarding`, and the local-first Room
  database in `core:data`.
- Artwork proxying: `core/data/.../server/ArtworkUrls.kt`.
- New-episode notifications are generated locally by Android's constrained
  `ContentRefreshWorker`; the native app does not register a browser Web Push
  endpoint or send notification data to a third party.
- Installation UUID generated and persisted by Android:
  `apps/android/core/data/src/main/kotlin/net/koalastuff/koalacast/core/data/auth/SecureAccountStore.kt`;
  sent during device login by `AccountRepository.kt`, and stored by
  `services/api/internal/server/handlers/auth.go`.
- Account deletion removes every user-scoped row:
  `services/api/internal/server/handlers/account.go`, with `account_test.go`
  asserting each user-scoped table empties.
- Independent synchronized-data deletion is implemented by authenticated
  `DELETE /api/v1/auth/data`. It keeps the user row, identity fields, sessions,
  and device credentials; disables Global Stats; deletes all synchronized
  content, usage, sync-metadata, and Web Push rows in one transaction; and
  advances `data_generation`. Stale pushes receive
  `409 DATA_GENERATION_MISMATCH`. `account_test.go`, Android
  `SyncRepositoryTest`, and web `sync-data-generation.spec.ts` verify the
  server and both clients. Technical access/security logs may remain for no
  more than seven days under the privacy policy.

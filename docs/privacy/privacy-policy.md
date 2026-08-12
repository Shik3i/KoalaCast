# Privacy Policy & Principles

KoalaCast is built around data minimization, local-first use, and the privacy
principles described below. The operator remains responsible for evaluating the
deployment's legal obligations.

## Core Privacy Rules

1. **Zero Behavioral Tracking**: No third-party analytics, no tracking pixels, no advertising scripts, and no user profiling.
2. **No Data Selling or Monetization**: User data is never sold, rented, or shared with third parties.
3. **No Personal Identification Required**: Accounts require only a self-chosen username and password. Email addresses and real names are never collected.
4. **Local-First Architecture**: The web application is 100% functional without an account. Subscriptions, playback positions, queue, and settings remain stored locally in your browser's IndexedDB.
5. **Direct Publisher Audio by Default**: Audio media files normally stream directly from podcast publishers to the browser or Android app. A self-hoster may explicitly enable `KC_AUDIO_EFFECTS_PROXY_ENABLED` as a last-resort browser relay for CORS-incompatible effects or downloads; it is disabled by default and Android playback remains direct.
6. **Privacy-Preserving Proxying**: Podcast artwork is proxied by default and can be loaded directly only when the listener disables artwork protection in Settings. RSS feed XML, chapters, and transcripts are fetched through KoalaCast backend endpoints where applicable. Artwork uses an in-memory RAM LRU cache; any optional audio relay is bounded, SSRF-protected, rate-limited, and not cached.
7. **Session IP Anonymization**: Active session records store only anonymized/truncated IP subnets (`/24` for IPv4, e.g. `192.168.1.0`, and `/48` for IPv6) to allow device recognition while preventing network tracking.
8. **Global Statistics Are Opt-In**: Public aggregate statistics and listener rankings include only signed-in accounts that explicitly enable participation. The setting is off by default and can be revoked at any time.

---

## Server Hosting & Retention

- **Official Host & Location**: The official service is deployed on ISO 27001-certified European infrastructure at Hetzner Online GmbH data centers in Germany. Self-hosters choose their own infrastructure and must publish matching operator, location and retention information.
- **Server Access Logs**: The official service keeps access logs for up to seven days. They may contain the connecting IP address, timestamp, HTTP request details, response status, referrer, and user agent and are used only for operation, troubleshooting, and abuse defense.
- **Password Security**: Passwords are hashed using OWASP-recommended **Argon2id** (`m=64MB, t=3, p=2`) with individual 16-byte random salts and server-side **HMAC-SHA256 Pepper** protection.

---

## Local Mode vs. Synced Mode

| Feature | Local Browser Mode | Synced Account Mode |
| :--- | :--- | :--- |
| **Account Required** | No | Yes (Username + Password) |
| **Data Storage Location** | Local browser IndexedDB | Server SQLite database; transport is protected by HTTPS, but stored sync data is not encrypted at the application layer |
| **Cross-Device Sync** | None | Automatic incremental sync |
| **Server Metadata Use** | RSS/search/metadata proxying | Subscriptions, favorites, playback state, and listening-session sync |
| **Audio Playback** | Direct publisher CDN by default; optional operator-enabled browser relay for CORS-incompatible effects/downloads | Same; Android playback remains direct |
| **Artwork & RSS Parsing** | Artwork proxied by default; RSS proxied | Artwork proxied by default; RSS proxied |

## Optional Global Statistics

- Participation is disabled by default.
- Opted-in synchronized sessions contribute to aggregate listening time, day/hour patterns, category totals, saved-time totals, and podcast rankings.
- The listener leaderboard publishes the account's chosen username with aggregate listening time, active days, and podcast count.
- Individual episodes, raw sessions, timestamps, device IDs, and internal account IDs are never returned by the public statistics endpoint.
- Opting out removes the account from all global aggregates and rankings immediately without deleting private synchronized listening statistics.

## Notifications and Background Checks

- New-episode notifications are opt-in per show and off by default.
- Enabling them registers a Web Push endpoint (a browser-issued URL plus two
  encryption keys) with the server, so it can wake the device while the site is
  closed. Turning the last show's notifications off unsubscribes the browser and
  deletes that registration from the server.
- Where the browser supports Periodic Background Sync, the service worker can
  check watched shows without any tab being open. It reads a small local mirror
  — the show's id and title and the episode ids already seen — that never leaves
  the device, and it requests the same public episode endpoint the app uses in
  the foreground. No additional data reaches the server.

## Data deletion choices

`/account` publicly documents both deletion paths; signing in is required only
to execute them. Both require the account password or recovery code again.

- **Delete synchronized data, keep the account:** permanently deletes
  subscriptions, favorites, playback state, listening sessions and history,
  queues, per-podcast and synchronized settings, statistics, Global Stats
  consent, sync logs/cursors/processed operations, Web Push registrations and
  the corresponding local client data and downloads. The account, username,
  password and recovery hashes, role, sessions and device credentials remain.
- **Delete account:** permanently deletes the account identity, credentials,
  sessions and all synchronized data. Clients also delete their local account
  copy and downloads.

Server-side synchronized-data deletion is one database transaction. It advances
a reset generation in that same transaction; stale or concurrent clients cannot
upload a pre-deletion copy afterward. Technical access and security logs are not
part of the synchronized account store and may remain for a maximum of seven
days, as stated above, before automatic deletion.

The live policy shown in the application is maintained in
[`apps/web/src/lib/data/privacy.ts`](../../apps/web/src/lib/data/privacy.ts).
That file is authoritative for the official hosted UI; self-hosters must adapt
operator, hosting, retention, and contact information to their own deployment.

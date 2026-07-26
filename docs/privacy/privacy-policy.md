# Privacy Policy & Principles

KoalaCast is built from the ground up around strict user privacy principles and full GDPR (DSGVO) compliance.

## Core Privacy Rules

1. **Zero Behavioral Tracking**: No third-party analytics, no tracking pixels, no advertising scripts, and no user profiling.
2. **No Data Selling or Monetization**: User data is never sold, rented, or shared with third parties.
3. **No Personal Identification Required**: Accounts require only a self-chosen username and password. Email addresses and real names are never collected.
4. **Local-First Architecture**: The web application is 100% functional without an account. Subscriptions, playback positions, queue, and settings remain stored locally in your browser's IndexedDB.
5. **Direct Publisher Audio Streaming**: Audio media files (MP3, AAC, M4A) stream directly from podcast publishers (e.g. Libsyn, Megaphone, Anchor, Podbean) to your web browser or app. KoalaCast backend servers never proxy, intercept, or log audio playback requests.
6. **Privacy-Preserving Proxying**: Podcast artwork, RSS feed XML, chapters, and transcripts are proxied via KoalaCast backend servers with an in-memory RAM LRU cache to shield your IP address from third-party image hosts.
7. **Session IP Anonymization**: Active session records store only anonymized/truncated IP subnets (`/24` for IPv4, e.g. `192.168.1.0`, and `/48` for IPv6) to allow device recognition while preventing network tracking.
8. **Global Statistics Are Opt-In**: Public aggregate statistics and listener rankings include only signed-in accounts that explicitly enable participation. The setting is off by default and can be revoked at any time.

---

## Server Hosting & Retention

- **Host & Location**: Self-hosted / deployed on ISO 27001-certified European infrastructure (Hetzner Online GmbH data centers in Germany).
- **Server Access Logs**: Standard web server access logs (containing anonymized IP subnets and request timestamps) are retained for a maximum of 7 days solely for DDoS defense and operational stability, after which they are automatically purged.
- **Password Security**: Passwords are hashed using OWASP-recommended **Argon2id** (`m=64MB, t=3, p=2`) with individual 16-byte random salts and server-side **HMAC-SHA256 Pepper** protection.

---

## Local Mode vs. Synced Mode

| Feature | Local Browser Mode | Synced Account Mode |
| :--- | :--- | :--- |
| **Account Required** | No | Yes (Username + Password) |
| **Data Storage Location** | Local browser IndexedDB | Encrypted SQLite Database |
| **Cross-Device Sync** | None | Automatic incremental sync |
| **Server Metadata Use** | Stateless RSS & search proxying | Subscriptions, queue & progress sync |
| **Audio Playback** | Direct publisher CDN | Direct publisher CDN |
| **Artwork & RSS Parsing** | Proxied via KoalaCast RAM LRU cache | Proxied via KoalaCast RAM LRU cache |

## Optional Global Statistics

- Participation is disabled by default.
- Opted-in synchronized sessions contribute to aggregate listening time, day/hour patterns, category totals, saved-time totals, and podcast rankings.
- The listener leaderboard publishes the account's chosen username with aggregate listening time, active days, and podcast count.
- Individual episodes, raw sessions, timestamps, device IDs, and internal account IDs are never returned by the public statistics endpoint.
- Opting out removes the account from all global aggregates and rankings immediately without deleting private synchronized listening statistics.

# Architecture & Configuration Precedence

## Technical Stack

- **Backend & Static Web Server**: Go with `net/http` and `go-chi/chi/v5` router, serving both REST API endpoints (`/api/v1/*`) and static web assets natively.
- **Database**: SQLite embedded engine in Write-Ahead Logging (`WAL`) mode with foreign key enforcement and busy timeout.
- **Web App**: SvelteKit 5 compiled to static Single-Page Application (`@sveltejs/adapter-static`), TypeScript, Vanilla CSS design system, IndexedDB for local storage, and browser Media Session API.
- **Image Proxy & Cache**: In-memory 100MB RAM LRU cache with Catmull-Rom downscaling and `singleflight` thundering herd protection.

## Configuration Precedence Hierarchy

KoalaCast enforces strict configuration rules to ensure self-hosters and security administrators have absolute control over server policy.

```text
1. Environment Variable Enforced Overrides (Highest Precedence)
   └── e.g., KC_REGISTRATION_ENABLED=false permanently blocks registration.

2. Database Admin Settings (Dynamic Config)
   └── Configurable via Admin UI when no environment override exists.

3. Environment File Defaults
   └── Default values provided in .env.example.
```

### Config Matrix

| Setting | Variable Name | Admin UI Editable? | Scope / Effect |
| :--- | :--- | :--- | :--- |
| Application Environment | `APP_ENV` | No | `production` by default; `development`/`dev` permits an ephemeral generated session secret |
| Server Port | `PORT` | No | Server binding port |
| Public URL | `PUBLIC_BASE_URL` | No | Canonical public origin |
| API URL | `API_BASE_URL` | No | Public API base advertised to clients |
| Log Level | `LOG_LEVEL` | No | `debug`, `info`, `warn` or `error` |
| Database | `DATABASE_PATH` | No | SQLite database path |
| Session Secret | `SESSION_SECRET` | No | HMAC key for session token signing |
| Pepper Secret | `PEPPER_SECRET` | No | HMAC-SHA256 secret key for Argon2id password hashing |
| Registration Toggle | `KC_REGISTRATION_ENABLED` | Conditional | Hard override if set; DB setting used if unset |
| Trusted Proxies | `TRUSTED_PROXIES` | No | CIDR list for reverse proxy IP header resolution |
| Secure Cookies | `SECURE_COOKIES` | No | Require secure session cookies; disable only for explicit local HTTP development |
| CORS Origins | `ALLOWED_CORS_ORIGINS` | No | Explicit origins for third-party/native cross-origin clients |
| Feed Worker Concurrency| `FEED_WORKER_CONCURRENCY` | No | Max parallel RSS feed fetchers |
| Feed Timeout | `FEED_REQUEST_TIMEOUT_MS` | No | Outbound feed request deadline |
| Feed Response Limit | `FEED_MAX_RESPONSE_BYTES` | No | Maximum RSS response body size |
| Stored Episodes per Feed | `FEED_MAX_STORED_EPISODES` | No | Recent metadata rows retained per podcast; user-state references are preserved |
| Feed Refresh Interval | `FEED_REFRESH_INTERVAL_MS` | No | Delay before a healthy notification-enabled feed is rechecked (clamped to 15 min – 24 h) |
| Podcast Index Credentials | `PODCAST_INDEX_KEY`, `PODCAST_INDEX_SECRET` | No | Optional credentials for catalog search |
| Initial Admin | `ADMIN_USERNAME`, `ADMIN_PASSWORD` | No | Create/promote an administrator at startup; password is used only for first creation |
| Web Push VAPID Keys | `WEB_PUSH_VAPID_PUBLIC_KEY`, `WEB_PUSH_VAPID_PRIVATE_KEY` | No | Enables authenticated server-to-browser push delivery |
| Web Push Contact | `WEB_PUSH_VAPID_SUBJECT` | No | VAPID contact URI, defaults to the public URL |
| Audio Relay | `KC_AUDIO_EFFECTS_PROXY_ENABLED` | No | Optional relay for CORS-blocked browser effects/downloads; disabled by default |

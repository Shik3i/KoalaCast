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
| Server Port | `PORT` | No | Server binding port |
| Public URL | `PUBLIC_BASE_URL` | No | Canonical public origin |
| Session Secret | `SESSION_SECRET` | No | HMAC key for session token signing |
| Pepper Secret | `PEPPER_SECRET` | No | HMAC-SHA256 secret key for Argon2id password hashing |
| Registration Toggle | `KC_REGISTRATION_ENABLED` | Conditional | Hard override if set; DB setting used if unset |
| Trusted Proxies | `TRUSTED_PROXIES` | No | CIDR list for reverse proxy IP header resolution |
| Feed Worker Concurrency| `FEED_WORKER_CONCURRENCY` | No | Max parallel RSS feed fetchers |
| Podcast Index Key | `PODCAST_INDEX_KEY` | No | API key for catalog search |

# KoalaCast API (`services/api`)

The Go REST backend and static web server for KoalaCast: podcast discovery/search, feed ingestion,
accounts, cross-device sync, OPML, privacy image proxy, and admin — backed by SQLite.

- **Language:** Go 1.26.5
- **Router:** [chi](https://github.com/go-chi/chi) v5
- **Storage:** SQLite (WAL mode, foreign keys, busy timeout)
- **Module:** `github.com/Shik3i/KoalaCast/services/api`

---

## Layout

```text
services/api/
├── cmd/server/          Entry point (main.go): config load, DB open, router, HTTP server
└── internal/
    ├── auth/            Argon2id hashing, recovery codes
    ├── config/          Environment configuration + precedence
    ├── db/              SQLite open/pragmas, migrations, seed data
    ├── itunes/          iTunes Top Charts, Search, and Lookup (feed-URL resolution)
    ├── podcastindex/    Optional Podcast Index API client
    ├── rss/             RSS/Atom parser + SSRF-safe HTTP client
    ├── worker/          Background feed-refresh worker pool
    └── server/
        ├── routes.go        Route table (API + static SPA file server)
        ├── server.go        HTTP server wiring
        ├── handlers/        Request handlers (podcasts, auth, sync, opml, admin, proxy, health)
        └── middleware/      Request ID, logging, CORS, Gzip compression, rate limiting
```

---

## Running

```bash
# From this directory
SESSION_SECRET=dev-secret-with-at-least-32-characters go run ./cmd/server
# Server on http://localhost:3000
```

Or via the repo-root Makefile: `make dev-api`.

### Build

```bash
CGO_ENABLED=1 go build -ldflags="-w -s" -o koalacast ./cmd/server
```

> `CGO_ENABLED=1` is required for the SQLite driver.

---

## Configuration

Configured entirely via environment variables (see [`../../.env.example`](../../.env.example)).
Precedence and defaults are documented in
[`../../docs/architecture/overview.md`](../../docs/architecture/overview.md).
`SESSION_SECRET` (32+ bytes) is mandatory.

---

## HTTP API & Static SPA Routes

Health probes: `GET /healthz`, `GET /readyz` (also under `/api/v1`).
Static Web SPA: `GET /*` (serves `/app/web/build` with `index.html` fallback).

All application routes are under `/api/v1`:

| Method | Path | Auth | Purpose |
| :--- | :--- | :--- | :--- |
| GET/HEAD | `/proxy/image?url=&w=` | — | Privacy-safe artwork resizer with 100MB RAM LRU cache |
| GET/HEAD | `/proxy/chapters` · `/proxy/transcript` | — | CORS-safe proxy for chapters & transcripts |
| GET/HEAD | `/proxy/audio` · `/proxy/audio/resolve` | — | Optional operator-enabled browser audio relay and redirect resolution |
| GET | `/podcasts/discover` | — | iTunes Top Charts (DB fallback) |
| GET | `/podcasts/search?q=` | — | Search via Podcast Index or iTunes |
| POST | `/podcasts/feed` | — | Add/ingest a podcast by RSS URL |
| GET | `/podcasts/{id}` | — | Podcast details (numeric iTunes IDs are resolved & ingested on demand) |
| GET | `/podcasts/{id}/episodes` | — | Paginated episodes |
| GET | `/episodes/{id}` | — | Single episode |
| GET | `/stats/global` | — | Opt-in aggregate statistics |
| POST | `/auth/register` · `/auth/login` · `/auth/device/login` · `/auth/recovery/verify` | rate-limited | Accounts |
| GET/POST/DELETE | `/auth/me` · `/auth/logout` · `/auth/sessions/{id}` | session/device token | Identity and session management |
| GET/DELETE | `/auth/export` · `/auth/data` · `/auth/account` | session/device token + confirmation | Export or delete synchronized/account data |
| GET/PUT | `/stats/preferences` | session/device token | Global-statistics consent |
| GET/POST/DELETE | `/push/config` · `/push/subscriptions` | session/device token | Web-Push configuration and registrations |
| GET/POST | `/sync` · `/sync/snapshot` · `/sync/merge` | session/device token | Incremental sync, recovery snapshot and initial merge |
| POST/GET | `/opml/import` · `/opml/export` | optional/session | Anonymous import or account export |
| * | `/admin/*` | admin | Admin dashboard operations |

The authoritative contract is [`packages/openapi/openapi.yaml`](../../packages/openapi/openapi.yaml).
Update it in the same PR as any endpoint change.

---

## Testing

```bash
go test -race ./...          # unit + integration
gofmt -l .                   # must print nothing
go vet ./...
```

Tests use fixtures and controlled test servers; they must remain deterministic
and must not depend on live catalog or publisher availability.

---

## Security & Caching Notes

- **RAM LRU Cache**: Remote artwork is resized using `golang.org/x/image/draw` and cached 100% in RAM with `singleflight` thundering herd protection.
- **SSRF Guard**: All outbound feed fetches go through the SSRF-safe client in `internal/rss` (`NewSafeHTTPClient`), which blocks loopback/private/link-local/CGNAT/metadata addresses and re-validates on redirects.
- **DoS Guard**: RSS bodies are size-limited (`FEED_MAX_RESPONSE_BYTES`) to prevent DoS.
- **Auth**: Passwords use Argon2id; sessions use HttpOnly cookies; auth endpoints are rate-limited.

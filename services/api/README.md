# KoalaCast API (`services/api`)

The Go REST backend for KoalaCast: podcast discovery/search, feed ingestion,
accounts, cross-device sync, OPML, and admin — backed by SQLite.

- **Language:** Go 1.25
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
        ├── routes.go        Route table
        ├── server.go        HTTP server wiring
        ├── handlers/        Request handlers (podcasts, auth, sync, opml, admin, health)
        └── middleware/      Request ID, logging, auth, rate limiting
```

---

## Running

```bash
# From this directory
SESSION_SECRET=dev-secret-with-at-least-32-characters go run ./cmd/server
# API on http://localhost:8080
```

Or via the repo-root Makefile: `make dev-api`.

### Build

```bash
CGO_ENABLED=1 go build -ldflags="-w -s" -o koalacast-api ./cmd/server
```

> `CGO_ENABLED=1` is required for the SQLite driver.

---

## Configuration

Configured entirely via environment variables (see [`../../.env.example`](../../.env.example)).
Precedence and defaults are documented in
[`../../docs/architecture/overview.md`](../../docs/architecture/overview.md).
`SESSION_SECRET` (32+ bytes) is mandatory.

---

## HTTP API

Health probes: `GET /healthz`, `GET /readyz` (also under `/api/v1`).

All application routes are under `/api/v1`:

| Method | Path | Auth | Purpose |
| :--- | :--- | :--- | :--- |
| GET | `/podcasts/discover` | — | iTunes Top Charts (DB fallback) |
| GET | `/podcasts/search?q=` | — | Search via Podcast Index or iTunes |
| POST | `/podcasts/feed` | — | Add/ingest a podcast by RSS URL |
| GET | `/podcasts/{id}` | — | Podcast details (numeric iTunes IDs are resolved & ingested on demand) |
| GET | `/podcasts/{id}/episodes` | — | Paginated episodes |
| GET | `/episodes/{id}` | — | Single episode |
| POST | `/auth/register` · `/auth/login` · `/auth/device/login` · `/auth/recovery/verify` | rate-limited | Accounts |
| GET/POST/DELETE | `/auth/me` · `/auth/logout` · `/auth/sessions` | session | Session management |
| GET/POST | `/sync` · `/sync/merge` | session | Cross-device sync |
| POST/GET | `/opml/import` · `/opml/export` | session | OPML |
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

> Some tests (e.g. iTunes search fallback) perform live network calls and may be
> flaky without internet access; CI runs them with connectivity.

---

## Security Notes

- **All** outbound feed fetches go through the SSRF-safe client in `internal/rss`
  (`NewSafeHTTPClient`), which blocks loopback/private/link-local/CGNAT/metadata
  addresses and re-validates on redirects.
- RSS bodies are size-limited (`FEED_MAX_RESPONSE_BYTES`) to prevent DoS.
- Passwords use Argon2id; sessions use HttpOnly cookies; auth endpoints are rate-limited.

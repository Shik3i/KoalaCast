# KoalaCast Implementation Boundaries

KoalaCast is a free, open-source, privacy-first podcast player with a Go API,
SvelteKit web client, native Android client, and SQLite persistence. This file
describes the current architectural boundaries; proposed features live in
[roadmap.md](roadmap.md) and [`api_todo.md`](../api_todo.md).

## Architecture and principles

- **Audio delivery:** browsers and native clients stream or download episode
  enclosures directly from publishers. KoalaCast does not proxy audio.
- **Metadata privacy:** search, RSS, artwork, chapters, and transcripts use the
  KoalaCast backend where applicable to avoid client-side CORS failures and
  reduce disclosure to metadata providers.
- **Local-first clients:** browsing and playback do not require an account.
- **Optional account sync:** the server can read and persist synchronized data;
  this is account-backed sync, not end-to-end encryption.
- **Same-origin production:** one Go process serves `/api/v1/*`, health probes,
  and the static SvelteKit SPA on port `3000`.
- **Precision:** playback durations and positions are integer milliseconds.
- **Licensing:** [MIT License](../LICENSE).

## Persisted server entities

The embedded migrations are the schema source of truth. They currently create:

- accounts, browser sessions, and revocable native device credentials;
- podcasts, feed aliases, episodes, subscriptions, favorites, playback states;
- queue/history/per-podcast-settings tables reserved for complete server-side
  materialization;
- listening sessions and the global-statistics consent fields;
- application settings, sync cursors, and the append-only sync mutation log.

## Synchronization boundary

The web client and server currently materialize these entity types:

- `subscription`
- `favorite`
- `playback_state`
- `listening_session`

Push operations are idempotent per user/device/client-operation ID. Pull uses a
monotonic server cursor, with conflict handling for passive progress versus
explicit playback actions. Queue and per-podcast settings are not yet
materialized by the sync handler even though schema foundations exist. Snapshot
recovery for a compacted cursor is also pending. Exact remaining API contracts:
[`api_todo.md`](../api_todo.md).

## Sources of truth

- REST contract: [`packages/openapi/openapi.yaml`](../packages/openapi/openapi.yaml)
- Database:
  [`services/api/internal/db/migrations/000001_initial_schema.up.sql`](../services/api/internal/db/migrations/000001_initial_schema.up.sql)
- Sync behavior: [`services/api/internal/server/handlers/sync.go`](../services/api/internal/server/handlers/sync.go)
- Web local data: [`apps/web/src/lib/idb/db.ts`](../apps/web/src/lib/idb/db.ts)
- Android status: [`apps/android/README.md`](../apps/android/README.md)

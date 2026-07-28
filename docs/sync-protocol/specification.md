# Synchronization Engine Specification

KoalaCast provides optional account-backed synchronization between web browsers
and native clients. The service persists synchronized data in SQLite; it is not
end-to-end encrypted.

## Implemented protocol

### Monotonic server cursor

- Every account has a monotonically increasing cursor in `user_sync_cursors`.
- Every accepted mutation appends a `sync_log` record with the next
  `server_cursor`.
- `GET /api/v1/sync?since_cursor=…` returns changes after the supplied cursor.

### Idempotent client operations

- Pushes include a client-generated `client_op_id` and `device_id`.
- The server deduplicates on `(user_id, device_id, client_op_id)`.
- A retry therefore succeeds without applying the same operation twice.

### Materialized entity types

- `subscription`
- `favorite`
- `playback_state`
- `listening_session`
- `queue`
- `podcast_settings`
- `settings`

The web and Android clients push and apply those types. Queue and portable
settings use last-writer-wins whole-object payloads; device-specific server URLs,
credentials, onboarding state, and storage paths remain local. Listening sessions
contain the aggregates needed for private and separately opt-in global
statistics.

### Playback conflict handling

Playback events carry `event_type`, `episode_id`, `position_ms`,
`playback_session_id`, `device_id`, `per_session_seq`, and timestamps.

- Passive `PROGRESS_TICK` events cannot regress a position in the same playback
  session or reopen a completed episode.
- Explicit `SEEK`, `RESTART`, `MARK_PLAYED`, and `MARK_UNPLAYED` events may
  override position and completion state.
- Within a playback session, a higher `per_session_seq` supersedes an older
  operation.

### Retention and full-resync signal

The background worker compacts old sync-log entries. A pull older than the
retained cursor returns `410 Gone` and `FULL_RESYNC_REQUIRED`.

### Snapshot recovery and pagination

- Pull responses expose `has_more` and a non-regressing `next_cursor`.
- Clients keep pulling until the page is exhausted.
- After `FULL_RESYNC_REQUIRED`, clients replace synchronized collections from
  `GET /api/v1/sync/snapshot` and resume incrementally from its cursor.
- Queue, podcast settings, and global settings are reconstructed from the latest
  non-deleted payload per entity in the append-only sync log.

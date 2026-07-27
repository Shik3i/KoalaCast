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

The web client pushes and applies those same four types. Listening sessions
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

## Known protocol gaps

The following are designs or schema foundations, not shipped sync behavior:

- a full snapshot endpoint after `FULL_RESYNC_REQUIRED`;
- lossless pagination when more than the pull limit is pending;
- server materialization and replay for queue operations;
- server materialization and replay for per-podcast settings;
- a complete local-to-account merge covering those pending entity types.

The requested wire contracts and acceptance criteria are maintained in
[`api_todo.md`](../../api_todo.md). Do not describe those items as implemented
until handler code, OpenAPI definitions, and client tests land together.

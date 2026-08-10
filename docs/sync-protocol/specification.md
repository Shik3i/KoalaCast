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

The web and Android clients push and apply those types. The queue uses a
last-writer-wins whole-object payload; the global `settings` blob is merged per
field (see below); device-specific server URLs, credentials, onboarding state,
and storage paths remain local. Listening sessions contain the aggregates needed
for private and separately opt-in global statistics.

### Settings conflict handling

The server never interprets a settings payload — it validates that the operation
carries a JSON object with a plausible `updated_at` and stores it verbatim — so
everything below is a contract between clients.

One `updated_at` for the whole object means the newest write wins all of it, and
that is wrong for fields that are independent of one another. Change the
interface language on the phone at 10:00 and the palette in the browser at 10:01,
and the browser's payload — newer as a whole — silently reverts the language.
Each field therefore carries its own timestamp:

```json
{
  "languages": ["de"],
  "palette": "fjord",
  "updated_at": 1786388000000,
  "field_updated_at": { "languages": 1786387000000, "palette": 1786388000000 }
}
```

- A field is accepted when its incoming timestamp is **strictly greater** than
  the receiver's timestamp for the same field. An exact tie changes nothing, so
  re-pulling one's own write is a no-op.
- A missing entry in `field_updated_at` falls back to the payload's `updated_at`,
  which is exactly what a client predating this contract means by its payload.
- A receiver with no per-field history falls back to its own stored `updated_at`
  for every field, so an installation upgrading into this contract does not look
  untouched and lose everything to the first payload that arrives.
- There is no blob-level rejection. A payload whose `updated_at` is older than
  the receiver's may still carry a field the receiver has never seen.
- The receiver's `updated_at` only ever moves forward, so fields of its own that
  are still waiting to be pushed keep looking newer than what it just merged in.
- `field_updated_at` is an owned key on both clients: it is never retained as a
  foreign key and written back twice.

Each client owns the fields it understands and passes the rest through
untouched, so the two clients' field sets may differ (`date_format` and
`ui_language` are web-only today) without either deleting the other's keys.

A client that predates this contract *and* edits a setting sends a genuine new
value under a stale inherited timestamp, and an updated peer may keep its own
value instead. That is a mixed-version window; it resolves as soon as the
setting is changed again from an updated client.

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

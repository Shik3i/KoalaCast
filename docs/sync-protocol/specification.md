# Synchronization Engine Specification

The KoalaCast synchronization engine provides reliable, cross-device state synchronization between web browsers and native clients (such as Android) while preserving precision and handling offline operations cleanly.

## Key Protocol Concepts

### 1. Monotonic Server Cursor
- Every user account maintains a monotonically increasing integer cursor `user_sync_cursor`.
- Any applied state mutation appends a record to `sync_log` assigned to `server_cursor = user_sync_cursor + 1`.

### 2. Client Operation Deduplication
- Pushes submit changesets containing a client-generated UUID `client_op_id` and `device_id`.
- The server enforces `UNIQUE(user_id, device_id, client_op_id)`. Re-transmitted pushes return success without re-executing state changes.

### 3. Playback Conflict Resolution Rules
All playback events include:
- `event_type` (`PROGRESS_TICK`, `SEEK`, `RESTART`, `MARK_PLAYED`, `MARK_UNPLAYED`)
- `episode_id`
- `position_ms` (integer milliseconds)
- `playback_session_id` (UUID)
- `device_id` (UUID)
- `per_session_seq` (int64)
- `client_timestamp` (informational)
- `server_receive_timestamp` (server timestamp)

#### Resolution Logic:
1. **Passive Ticks** (`PROGRESS_TICK`):
   - Cannot move position backwards relative to existing server position if within the same `playback_session_id`.
   - Cannot reopen an episode already marked `completed=true`.
2. **Explicit User Actions** (`SEEK`, `RESTART`, `MARK_PLAYED`, `MARK_UNPLAYED`):
   - Overrides existing position and completed state regardless of position progression.
3. **Session Sequence**:
   - Higher `per_session_seq` values within a `playback_session_id` supersede lower sequence values.

### 4. Queue Synchronization Strategy
Queue operations use stable queue item UUIDs and explicit discrete operations:
- `ADD_AFTER(item_id, ref_item_id)`
- `ADD_TO_BEGINNING(item_id)`
- `ADD_TO_END(item_id)`
- `REMOVE_ITEM(item_id)`
- `MOVE_AFTER(item_id, ref_item_id)`
- `CLEAR_QUEUE`

The server applies queue ops sequentially in monotonic cursor order using fractional sequence indexing. Missing reference IDs fall back to `ADD_TO_END`.

### 5. Sync Log Retention & Compaction
- The server retains `sync_log` entries up to a configured threshold (e.g. 10,000 entries or 90 days).
- If a client requests `GET /api/v1/sync?since_cursor=C` where `C` is older than the server's retained log minimum, the server returns HTTP `410 Gone` with `{ "code": "FULL_RESYNC_REQUIRED" }`.
- The client then resets its local sync state from a full state snapshot endpoint (`GET /api/v1/sync/snapshot`).

### 6. Local Mode to Synced Mode Data Merge
- Upon sign up/login, the web client prompts the user to merge local IndexedDB data with their server account.
- Merging submits an idempotent batch request to `/api/v1/sync/merge`.
- Subscriptions and favorites are unioned; higher `position_ms` is preserved; local queue items append to remote queue.
- **Local IndexedDB data is cleared ONLY after receiving HTTP 200 confirmation** from the server merge endpoint.

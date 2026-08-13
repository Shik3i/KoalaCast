package db

import (
	"context"
	"time"
)

const syncOperationLedgerRetention = 30 * 24 * time.Hour

// CompactSyncLog bounds the append-only sync_log by deleting every entry that has
// been superseded by a newer one for the same (user, entity_type, entity_id).
//
// This is safe for the sync protocol:
//   - The newest entry per entity is retained, so a full pull (since_cursor=0)
//     still reconstructs the complete current state (latest op per entity acts as
//     a snapshot) and an incremental pull still sees every change after its cursor.
//   - Superseded playback-progress entries carry stale client_op_ids that a client
//     never re-pushes (its op_id is derived from current local state), so the
//     deduplication guarantee is preserved and deleted rows don't resurrect.
//
// Without it, high-frequency PROGRESS_TICK updates would grow the log unbounded.
func (db *DB) CompactSyncLog(ctx context.Context) (int64, error) {
	tx, err := db.SQL.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `
		DELETE FROM sync_log
		WHERE id IN (
			SELECT id FROM (
				SELECT id, ROW_NUMBER() OVER (
					PARTITION BY user_id, entity_type, entity_id
					ORDER BY
						CASE WHEN entity_type = 'playback_state' THEN server_cursor END DESC,
						CASE WHEN entity_type <> 'playback_state' THEN client_timestamp END DESC,
						CASE WHEN entity_type <> 'playback_state' THEN device_id END DESC,
						server_cursor DESC,
						id DESC
				) AS conflict_rank
				FROM sync_log
			) WHERE conflict_rank > 1
		)
	`)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	// Idempotency only needs to outlive every realistic offline retry. Keeping a
	// rolling month prevents an authenticated client from growing this table
	// forever with unique operation IDs.
	if _, err := tx.ExecContext(ctx, `
		DELETE FROM processed_sync_operations WHERE processed_at < ?
	`, time.Now().Add(-syncOperationLedgerRetention).UnixMilli()); err != nil {
		return 0, err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT OR IGNORE INTO user_sync_cursors (
			user_id, current_cursor, min_retained_cursor, protocol_version, client_schema_version
		)
		SELECT user_id, MAX(server_cursor), 0, 1, 1
		FROM sync_log
		GROUP BY user_id
	`); err != nil {
		return 0, err
	}
	// A client whose cursor points before the oldest retained mutation can no
	// longer receive a complete incremental history. Mark that boundary so Pull
	// returns 410 and the client uses the authoritative snapshot instead of
	// silently advancing across deleted cursors.
	if _, err := tx.ExecContext(ctx, `
		UPDATE user_sync_cursors
		SET min_retained_cursor = COALESCE((
			SELECT MIN(server_cursor)
			FROM sync_log
			WHERE sync_log.user_id = user_sync_cursors.user_id
		), current_cursor)
	`); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return n, nil
}

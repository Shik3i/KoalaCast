package db

import "context"

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
	res, err := db.SQL.ExecContext(ctx, `
		DELETE FROM sync_log
		WHERE id NOT IN (
			SELECT MAX(id) FROM sync_log GROUP BY user_id, entity_type, entity_id
		)
	`)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return n, nil
}

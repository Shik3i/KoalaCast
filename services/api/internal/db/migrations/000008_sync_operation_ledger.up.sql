CREATE TABLE IF NOT EXISTS processed_sync_operations (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    client_op_id TEXT NOT NULL,
    server_cursor INTEGER NOT NULL,
    processed_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, device_id, client_op_id)
);

CREATE INDEX IF NOT EXISTS idx_processed_sync_operations_user_cursor
    ON processed_sync_operations(user_id, server_cursor);

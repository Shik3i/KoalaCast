CREATE INDEX IF NOT EXISTS idx_sync_log_snapshot
    ON sync_log(user_id, entity_type, entity_id, server_cursor DESC);

CREATE INDEX IF NOT EXISTS idx_sync_log_conflict_order
    ON sync_log(user_id, entity_type, entity_id, client_timestamp DESC, device_id DESC, server_cursor DESC);

CREATE INDEX IF NOT EXISTS idx_listening_sessions_snapshot
    ON listening_sessions(user_id, episode_id, ended_at DESC);

-- Credentials created before expiry enforcement used 0 as "never expires".
-- Give them the same 90-day lifetime as newly issued native credentials.
UPDATE device_credentials
SET expires_at = created_at + (90 * 24 * 60 * 60 * 1000)
WHERE expires_at = 0;

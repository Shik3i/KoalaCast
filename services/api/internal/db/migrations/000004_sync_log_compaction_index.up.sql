-- Speeds up periodic sync_log compaction, which keeps only the newest entry per
-- (user, entity_type, entity_id) so the mutation log can't grow without bound from
-- high-frequency playback progress ticks.
CREATE INDEX IF NOT EXISTS idx_sync_log_entity ON sync_log(user_id, entity_type, entity_id);

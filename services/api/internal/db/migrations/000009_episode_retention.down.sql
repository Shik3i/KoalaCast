DROP INDEX IF EXISTS idx_podcasts_refresh_activity;
ALTER TABLE podcasts DROP COLUMN last_accessed_at;

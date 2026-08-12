DROP TRIGGER IF EXISTS error_events_count_retention;
DROP TRIGGER IF EXISTS error_events_time_retention;
DROP INDEX IF EXISTS idx_error_events_status_occurred;
DROP INDEX IF EXISTS idx_error_events_occurred_at;
DROP TABLE IF EXISTS error_events;

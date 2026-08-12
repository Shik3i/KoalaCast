CREATE TABLE IF NOT EXISTS error_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at INTEGER NOT NULL,
    status_code INTEGER NOT NULL,
    method TEXT NOT NULL,
    path TEXT NOT NULL,
    message TEXT NOT NULL DEFAULT '',
    request_id TEXT NOT NULL DEFAULT '',
    user_id TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT 'http'
);

CREATE INDEX IF NOT EXISTS idx_error_events_occurred_at
    ON error_events(occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_error_events_status_occurred
    ON error_events(status_code, occurred_at DESC);

-- Keep diagnostics useful without allowing scanners and repeated client errors
-- to grow the operational database forever. Prune in chunks to avoid doing a
-- full count/delete cycle for every row once the cap is reached.
CREATE TRIGGER IF NOT EXISTS error_events_time_retention
AFTER INSERT ON error_events
BEGIN
    DELETE FROM error_events
    WHERE occurred_at < (unixepoch('now') * 1000) - 604800000;
END;

CREATE TRIGGER IF NOT EXISTS error_events_count_retention
AFTER INSERT ON error_events
WHEN NEW.id % 250 = 0
BEGIN
    DELETE FROM error_events
    WHERE id IN (
        SELECT id FROM error_events
        ORDER BY occurred_at DESC, id DESC
        LIMIT -1 OFFSET 5000
    );
END;

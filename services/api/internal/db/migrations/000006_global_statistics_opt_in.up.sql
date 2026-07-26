ALTER TABLE users ADD COLUMN global_stats_opt_in INTEGER NOT NULL DEFAULT 0
    CHECK (global_stats_opt_in IN (0, 1));

ALTER TABLE users ADD COLUMN global_stats_opt_in_at INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_users_global_stats_opt_in
    ON users(global_stats_opt_in, is_suspended);

CREATE INDEX IF NOT EXISTS idx_listening_sessions_started
    ON listening_sessions(started_at);

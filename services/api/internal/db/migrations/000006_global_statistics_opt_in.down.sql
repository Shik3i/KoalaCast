DROP INDEX IF EXISTS idx_users_global_stats_opt_in;
DROP INDEX IF EXISTS idx_listening_sessions_started;
ALTER TABLE users DROP COLUMN global_stats_opt_in_at;
ALTER TABLE users DROP COLUMN global_stats_opt_in;

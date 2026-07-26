CREATE TABLE IF NOT EXISTS listening_sessions (
    id TEXT NOT NULL,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id TEXT NOT NULL,
    podcast_id TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    podcast_title TEXT NOT NULL DEFAULT '',
    categories_json TEXT NOT NULL DEFAULT '[]',
    started_at INTEGER NOT NULL,
    ended_at INTEGER NOT NULL,
    wall_clock_ms INTEGER NOT NULL DEFAULT 0 CHECK (wall_clock_ms >= 0),
    audio_listened_ms INTEGER NOT NULL DEFAULT 0 CHECK (audio_listened_ms >= 0),
    speed_saved_ms INTEGER NOT NULL DEFAULT 0 CHECK (speed_saved_ms >= 0),
    silence_saved_ms INTEGER NOT NULL DEFAULT 0 CHECK (silence_saved_ms >= 0),
    manual_skipped_ms INTEGER NOT NULL DEFAULT 0 CHECK (manual_skipped_ms >= 0),
    intro_outro_skipped_ms INTEGER NOT NULL DEFAULT 0 CHECK (intro_outro_skipped_ms >= 0),
    speed_weighted_ms INTEGER NOT NULL DEFAULT 0 CHECK (speed_weighted_ms >= 0),
    sync_version INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, id)
);

CREATE INDEX IF NOT EXISTS idx_listening_sessions_user_started
    ON listening_sessions(user_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_listening_sessions_user_podcast
    ON listening_sessions(user_id, podcast_id);

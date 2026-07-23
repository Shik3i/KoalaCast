-- Initial Schema Migration for KoalaCast

-- Users Table
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    recovery_code_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',
    is_suspended INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Web Sessions Table
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    device_name TEXT NOT NULL DEFAULT '',
    device_type TEXT NOT NULL DEFAULT 'web',
    truncated_ip TEXT NOT NULL DEFAULT '',
    sanitized_user_agent TEXT NOT NULL DEFAULT '',
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    last_used_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

-- Revocable Device Credentials Table (For API & Native Mobile Clients)
CREATE TABLE IF NOT EXISTS device_credentials (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    token_hash TEXT NOT NULL UNIQUE,
    client_type TEXT NOT NULL DEFAULT 'android',
    client_schema_version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    last_sync_at INTEGER NOT NULL,
    is_revoked INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, device_id)
);

-- Podcasts Table
CREATE TABLE IF NOT EXISTS podcasts (
    id TEXT PRIMARY KEY,
    feed_url TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    author TEXT NOT NULL DEFAULT '',
    artwork_url TEXT NOT NULL DEFAULT '',
    link TEXT NOT NULL DEFAULT '',
    language TEXT NOT NULL DEFAULT '',
    explicit INTEGER NOT NULL DEFAULT 0,
    copyright TEXT NOT NULL DEFAULT '',
    update_frequency_ms INTEGER NOT NULL DEFAULT 86400000,
    last_fetch_attempt_at INTEGER NOT NULL DEFAULT 0,
    last_successful_fetch_at INTEGER NOT NULL DEFAULT 0,
    next_scheduled_fetch_at INTEGER NOT NULL DEFAULT 0,
    etag TEXT NOT NULL DEFAULT '',
    last_modified TEXT NOT NULL DEFAULT '',
    consecutive_error_count INTEGER NOT NULL DEFAULT 0,
    last_error_category TEXT NOT NULL DEFAULT '',
    last_http_status INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Podcast Feed Aliases Table
CREATE TABLE IF NOT EXISTS podcast_aliases (
    id TEXT PRIMARY KEY,
    alias_url TEXT NOT NULL UNIQUE,
    target_podcast_id TEXT NOT NULL REFERENCES podcasts(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL
);

-- Episodes Table
CREATE TABLE IF NOT EXISTS episodes (
    id TEXT PRIMARY KEY,
    podcast_id TEXT NOT NULL REFERENCES podcasts(id) ON DELETE CASCADE,
    guid TEXT NOT NULL DEFAULT '',
    fallback_hash TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    pub_date INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    enclosure_url TEXT NOT NULL,
    enclosure_type TEXT NOT NULL DEFAULT '',
    enclosure_length INTEGER NOT NULL DEFAULT 0,
    artwork_url TEXT NOT NULL DEFAULT '',
    episode_number INTEGER NOT NULL DEFAULT 0,
    season_number INTEGER NOT NULL DEFAULT 0,
    episode_type TEXT NOT NULL DEFAULT 'full',
    explicit INTEGER NOT NULL DEFAULT 0,
    link TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_episodes_podcast_pubdate ON episodes(podcast_id, pub_date DESC);

-- Subscriptions Table
CREATE TABLE IF NOT EXISTS subscriptions (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    podcast_id TEXT NOT NULL REFERENCES podcasts(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    sync_version INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, podcast_id)
);

-- Playback States Table
CREATE TABLE IF NOT EXISTS playback_states (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id TEXT NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
    position_ms INTEGER NOT NULL DEFAULT 0,
    completed INTEGER NOT NULL DEFAULT 0,
    progress_percent REAL NOT NULL DEFAULT 0.0,
    event_type TEXT NOT NULL DEFAULT 'PROGRESS_TICK',
    playback_session_id TEXT NOT NULL DEFAULT '',
    device_id TEXT NOT NULL DEFAULT '',
    per_session_seq INTEGER NOT NULL DEFAULT 0,
    client_timestamp INTEGER NOT NULL DEFAULT 0,
    server_receive_timestamp INTEGER NOT NULL DEFAULT 0,
    sync_version INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, episode_id)
);

-- Favorites Table
CREATE TABLE IF NOT EXISTS favorites (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id TEXT NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    sync_version INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, episode_id)
);

-- Queue Items Table
CREATE TABLE IF NOT EXISTS queue_items (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id TEXT NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
    position_order REAL NOT NULL DEFAULT 0.0,
    added_at INTEGER NOT NULL,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    sync_version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_queue_user_order ON queue_items(user_id, position_order ASC);

-- History Entries Table
CREATE TABLE IF NOT EXISTS history_entries (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id TEXT NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
    played_at INTEGER NOT NULL,
    position_ms INTEGER NOT NULL DEFAULT 0,
    sync_version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_history_user_played ON history_entries(user_id, played_at DESC);

-- Per Podcast Settings Table
CREATE TABLE IF NOT EXISTS per_podcast_settings (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    podcast_id TEXT NOT NULL REFERENCES podcasts(id) ON DELETE CASCADE,
    playback_speed REAL NOT NULL DEFAULT 1.0,
    sync_version INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, podcast_id)
);

-- App Settings Table
CREATE TABLE IF NOT EXISTS app_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

-- User Sync Cursors Table
CREATE TABLE IF NOT EXISTS user_sync_cursors (
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    current_cursor INTEGER NOT NULL DEFAULT 0
);

-- Sync Log Table (Append-Only Sync Mutation Log with Operation Deduplication)
CREATE TABLE IF NOT EXISTS sync_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    client_op_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    action TEXT NOT NULL,
    payload_json TEXT NOT NULL DEFAULT '{}',
    client_timestamp INTEGER NOT NULL,
    server_timestamp INTEGER NOT NULL,
    server_cursor INTEGER NOT NULL,
    UNIQUE(user_id, device_id, client_op_id)
);
CREATE INDEX IF NOT EXISTS idx_sync_log_user_cursor ON sync_log(user_id, server_cursor ASC);

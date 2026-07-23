-- Initial Schema Down Migration

DROP TABLE IF EXISTS sync_log;
DROP TABLE IF EXISTS user_sync_cursors;
DROP TABLE IF EXISTS app_settings;
DROP TABLE IF EXISTS per_podcast_settings;
DROP TABLE IF EXISTS history_entries;
DROP TABLE IF EXISTS queue_items;
DROP TABLE IF EXISTS favorites;
DROP TABLE IF EXISTS playback_states;
DROP TABLE IF EXISTS subscriptions;
DROP TABLE IF EXISTS episodes;
DROP TABLE IF EXISTS podcast_aliases;
DROP TABLE IF EXISTS podcasts;
DROP TABLE IF EXISTS device_credentials;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS users;

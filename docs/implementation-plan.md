# KoalaCast Implementation Plan

KoalaCast is a completely free, open-source, privacy-first podcast player built with a Go REST backend, SvelteKit (Svelte 5) web application, and SQLite database. It features local-first browser playback as well as optional **browser and cross-device synchronization** with zero audio proxying, zero tracking, and zero advertising.

## Architecture & Principles

- **Audio Delivery**: Direct client-to-publisher streaming. The server never proxies or stores audio files.
- **Licensing**: [MIT License](../../LICENSE).
- **Durations & Positions**: Stored strictly as **integer milliseconds** (`int64` / `INTEGER`).
- **Same-Origin Production Deployment**: Caddy reverse proxy routes `/` to SvelteKit and `/api/v1/*` to Go API.

## Database Schema & Core Entities

- `users` (id, username, normalized_username [UNIQUE], password_hash, recovery_code_hash, role, is_suspended)
- `sessions` (id, user_id, token_hash, device_name, device_type, truncated_ip, sanitized_user_agent, expires_at)
- `device_credentials` (id, user_id, device_id, name, token_hash, client_type, client_schema_version, is_revoked)
- `podcasts` (id, feed_url [UNIQUE], title, description, author, artwork_url, link, language, explicit, etag, last_modified)
- `podcast_aliases` (id, alias_url [UNIQUE], target_podcast_id)
- `episodes` (id, podcast_id, stable_identity_key [UNIQUE per podcast], guid, fallback_hash, title, description, content_encoded, pub_date, has_pub_date, duration_ms, enclosure_url)
- `subscriptions` (user_id, podcast_id, is_deleted, sync_version)
- `playback_states` (user_id, episode_id, position_ms, completed, progress_percent, event_type, playback_session_id, device_id, per_session_seq, sync_version)
- `favorites` (user_id, episode_id, is_deleted, sync_version)
- `queue_items` (id, user_id, episode_id, position_order, is_deleted, sync_version)
- `history_entries` (id, user_id, episode_id, played_at, position_ms, sync_version)
- `per_podcast_settings` (user_id, podcast_id, playback_speed, sync_version)
- `user_sync_cursors` (user_id, current_cursor, min_retained_cursor, protocol_version, client_schema_version)
- `sync_log` (id, user_id, device_id, client_op_id [UNIQUE per user/device], entity_type, entity_id, action, payload_json, server_cursor)

## Synchronization Engine Principles

- Monotonic per-user server cursor (`user_sync_cursors`).
- Client operation deduplication via `UNIQUE(user_id, device_id, client_op_id)`.
- Explicit conflict resolution distinguishing passive progress ticks from explicit seek/restart/played actions.
- Discrete queue operations (`ADD_AFTER`, `ADD_TO_BEGINNING`, `ADD_TO_END`, `REMOVE_ITEM`, `MOVE_AFTER`, `CLEAR_QUEUE`).

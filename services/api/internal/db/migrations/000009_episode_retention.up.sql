ALTER TABLE podcasts ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0;

UPDATE podcasts
SET last_accessed_at = CASE
    WHEN updated_at > 0 THEN updated_at
    ELSE created_at
END;

-- Many publishers put the same HTML into both fields. The clients already fall
-- back from content_encoded to description, so keeping an identical second copy
-- only doubles that episode's show-note payload.
UPDATE episodes
SET content_encoded = ''
WHERE content_encoded <> '' AND content_encoded = description;

-- Keep a useful recent window per show plus every episode that carries user
-- state. Old unreferenced feed rows are cache, not user data.
DELETE FROM episodes
WHERE id IN (
    SELECT candidate.id
    FROM episodes candidate
    WHERE candidate.id NOT IN (
        SELECT recent.id
        FROM episodes recent
        WHERE recent.podcast_id = candidate.podcast_id
        ORDER BY recent.has_pub_date DESC, recent.pub_date DESC, recent.created_at DESC
        LIMIT 200
    )
)
AND id NOT IN (SELECT episode_id FROM playback_states)
AND id NOT IN (SELECT episode_id FROM favorites)
AND id NOT IN (SELECT episode_id FROM queue_items)
AND id NOT IN (SELECT episode_id FROM history_entries)
AND id NOT IN (SELECT episode_id FROM listening_sessions);

CREATE INDEX IF NOT EXISTS idx_podcasts_refresh_activity
ON podcasts(last_accessed_at, next_scheduled_fetch_at);

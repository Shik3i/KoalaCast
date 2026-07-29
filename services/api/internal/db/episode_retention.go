package db

import (
	"context"
	"database/sql"
)

const DefaultMaxStoredEpisodesPerPodcast = 200

type contextExecer interface {
	ExecContext(context.Context, string, ...any) (sql.Result, error)
}

// PrunePodcastEpisodes removes old feed cache rows while preserving anything
// referenced by durable user state. Deleted SQLite pages are reused immediately;
// OpenDB reclaims the file itself when enough free pages accumulate.
func PrunePodcastEpisodes(
	ctx context.Context,
	execer contextExecer,
	podcastID string,
	limit int,
) (int64, error) {
	if limit <= 0 {
		limit = DefaultMaxStoredEpisodesPerPodcast
	}
	result, err := execer.ExecContext(ctx, `
		DELETE FROM episodes
		WHERE podcast_id = ?
		  AND id NOT IN (
			SELECT id
			FROM episodes
			WHERE podcast_id = ?
			ORDER BY has_pub_date DESC, pub_date DESC, created_at DESC
			LIMIT ?
		  )
		  AND id NOT IN (SELECT episode_id FROM playback_states)
		  AND id NOT IN (SELECT episode_id FROM favorites)
		  AND id NOT IN (SELECT episode_id FROM queue_items)
		  AND id NOT IN (SELECT episode_id FROM history_entries)
		  AND id NOT IN (SELECT episode_id FROM listening_sessions)
	`, podcastID, podcastID, limit)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

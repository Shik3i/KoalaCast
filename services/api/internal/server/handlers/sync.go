package handlers

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
)

type SyncHandler struct {
	DB *db.DB
}

type SyncPushOperation struct {
	ClientOpID      string          `json:"client_op_id"`
	DeviceID        string          `json:"device_id"`
	EntityType      string          `json:"entity_type"` // "subscription", "playback_state", "listening_session", "favorite", "queue", "settings"
	Action          string          `json:"action"`      // "upsert", "delete", "queue_op"
	EntityID        string          `json:"entity_id"`
	Payload         json.RawMessage `json:"payload"`
	ClientTimestamp int64           `json:"client_timestamp"`
}

type SyncPushRequest struct {
	Operations          []SyncPushOperation `json:"operations"`
	ClientSchemaVersion int                 `json:"client_schema_version"`
}

type PlaybackStatePayload struct {
	EpisodeID         string  `json:"episode_id"`
	PositionMS        int64   `json:"position_ms"`
	Completed         bool    `json:"completed"`
	ProgressPercent   float64 `json:"progress_percent"`
	EventType         string  `json:"event_type"` // "PROGRESS_TICK", "SEEK", "RESTART", "MARK_PLAYED", "MARK_UNPLAYED"
	PlaybackSessionID string  `json:"playback_session_id"`
	DeviceID          string  `json:"device_id"`
	PerSessionSeq     int64   `json:"per_session_seq"`
	ClientTimestamp   int64   `json:"client_timestamp"`
}

type QueueOperationPayload struct {
	OpType          string `json:"op_type"` // "ADD_AFTER", "ADD_TO_BEGINNING", "ADD_TO_END", "REMOVE_ITEM", "MOVE_AFTER", "CLEAR_QUEUE"
	ItemID          string `json:"item_id"`
	EpisodeID       string `json:"episode_id"`
	ReferenceItemID string `json:"reference_item_id"`
}

type ListeningSessionPayload struct {
	ID                  string   `json:"id"`
	EpisodeID           string   `json:"episode_id"`
	PodcastID           string   `json:"podcast_id"`
	Title               string   `json:"title"`
	PodcastTitle        string   `json:"podcast_title"`
	Categories          []string `json:"categories"`
	StartedAt           int64    `json:"started_at"`
	EndedAt             int64    `json:"ended_at"`
	WallClockMS         int64    `json:"wall_clock_ms"`
	AudioListenedMS     int64    `json:"audio_listened_ms"`
	SpeedSavedMS        int64    `json:"speed_saved_ms"`
	SilenceSavedMS      int64    `json:"silence_saved_ms"`
	ManualSkippedMS     int64    `json:"manual_skipped_ms"`
	IntroOutroSkippedMS int64    `json:"intro_outro_skipped_ms"`
	SpeedWeightedMS     int64    `json:"speed_weighted_ms"`
}

type SyncLogEntry struct {
	ID              int64           `json:"id"`
	DeviceID        string          `json:"device_id"`
	ClientOpID      string          `json:"client_op_id"`
	EntityType      string          `json:"entity_type"`
	EntityID        string          `json:"entity_id"`
	Action          string          `json:"action"`
	Payload         json.RawMessage `json:"payload"`
	ClientTimestamp int64           `json:"client_timestamp"`
	ServerTimestamp int64           `json:"server_timestamp"`
	ServerCursor    int64           `json:"server_cursor"`
}

func (h *SyncHandler) Pull(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	sinceCursor := int64(0)
	if raw := r.URL.Query().Get("since_cursor"); raw != "" {
		sc, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || sc < 0 {
			http.Error(w, `{"error":"since_cursor must be a non-negative integer"}`, http.StatusBadRequest)
			return
		}
		sinceCursor = sc
	}
	limit := 500
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 || parsed > 500 {
			http.Error(w, `{"error":"limit must be between 1 and 500"}`, http.StatusBadRequest)
			return
		}
		limit = parsed
	}

	// Check if client cursor is older than min_retained_cursor
	var currentCursor, minRetainedCursor int64
	err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT current_cursor, min_retained_cursor FROM user_sync_cursors WHERE user_id = ?
	`, authUser.ID).Scan(&currentCursor, &minRetainedCursor)
	if err == sql.ErrNoRows {
		if _, err := h.DB.SQL.ExecContext(r.Context(), "INSERT OR IGNORE INTO user_sync_cursors (user_id, current_cursor, min_retained_cursor, protocol_version, client_schema_version) VALUES (?, 0, 0, 1, 1)", authUser.ID); err != nil {
			http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
			return
		}
		currentCursor = 0
		minRetainedCursor = 0
	} else if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	if sinceCursor > 0 && sinceCursor < minRetainedCursor {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusGone) // 410 Gone -> Requires Full Resync Snapshot
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"code":              "FULL_RESYNC_REQUIRED",
			"message":           "Requested sync cursor is older than server retained mutation log history. Full state snapshot required.",
			"min_server_cursor": minRetainedCursor,
			"current_cursor":    currentCursor,
		})
		return
	}

	// Fetch changesets from sync_log where server_cursor > sinceCursor
	rows, err := h.DB.SQL.QueryContext(r.Context(), `
		SELECT id, device_id, client_op_id, entity_type, entity_id, action, payload_json, client_timestamp, server_timestamp, server_cursor
		FROM sync_log
		WHERE user_id = ? AND server_cursor > ?
		ORDER BY server_cursor ASC
		LIMIT ?
	`, authUser.ID, sinceCursor, limit)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	changesets := make([]SyncLogEntry, 0)
	nextCursor := sinceCursor
	for rows.Next() {
		var item SyncLogEntry
		var payloadStr string
		if err := rows.Scan(&item.ID, &item.DeviceID, &item.ClientOpID, &item.EntityType, &item.EntityID, &item.Action, &payloadStr, &item.ClientTimestamp, &item.ServerTimestamp, &item.ServerCursor); err != nil {
			http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
			return
		}
		item.Payload = json.RawMessage(payloadStr)
		changesets = append(changesets, item)
		nextCursor = item.ServerCursor
	}
	if err := rows.Err(); err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"since_cursor":   sinceCursor,
		"next_cursor":    nextCursor,
		"current_cursor": currentCursor,
		"has_more":       nextCursor < currentCursor,
		"changesets":     changesets,
	})
}

func (h *SyncHandler) Snapshot(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	tx, err := h.DB.SQL.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if err != nil {
		http.Error(w, `{"error":"transaction error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	var cursor int64
	err = tx.QueryRowContext(r.Context(), `SELECT current_cursor FROM user_sync_cursors WHERE user_id = ?`, authUser.ID).Scan(&cursor)
	if err != nil && err != sql.ErrNoRows {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	subscriptions, err := snapshotRows(tx, r.Context(), `
		SELECT s.podcast_id, s.created_at, s.updated_at, s.sync_version,
		       COALESCE((
		           SELECT COALESCE(NULLIF(json_extract(sl.payload_json, '$.added_at'), 0), NULLIF(sl.client_timestamp, 0))
		           FROM sync_log sl
		           WHERE sl.user_id = s.user_id AND sl.entity_type = 'subscription'
		             AND sl.entity_id = s.podcast_id
		           ORDER BY sl.server_cursor DESC LIMIT 1
		       ), s.created_at),
		       COALESCE((
		           SELECT json_extract(sl.payload_json, '$.inbox_mode')
		           FROM sync_log sl
		           WHERE sl.user_id = s.user_id AND sl.entity_type = 'subscription'
		             AND sl.entity_id = s.podcast_id
		           ORDER BY sl.server_cursor DESC LIMIT 1
		       ), 'all'),
		       p.feed_url, p.title, p.description, p.author, p.artwork_url,
		       p.link, p.language, p.explicit, p.copyright
		FROM subscriptions s
		JOIN podcasts p ON p.id = s.podcast_id
		WHERE s.user_id = ? AND s.is_deleted = 0
		ORDER BY s.podcast_id`, authUser.ID, func(rows *sql.Rows) (any, error) {
		var podcastID, inboxMode, feedURL, title, description, author, artworkURL, link, language, copyright string
		var createdAt, updatedAt, syncVersion, addedAt int64
		var explicit int
		if err := rows.Scan(
			&podcastID, &createdAt, &updatedAt, &syncVersion, &addedAt, &inboxMode,
			&feedURL, &title, &description, &author, &artworkURL,
			&link, &language, &explicit, &copyright,
		); err != nil {
			return nil, err
		}
		return map[string]any{
			"id": podcastID, "podcast_id": podcastID, "feed_url": feedURL,
			"title": title, "description": description, "author": author,
			"artwork_url": artworkURL, "link": link, "language": language,
			"explicit": explicit == 1, "copyright": copyright,
			"inbox_mode": inboxMode,
			"added_at":   addedAt, "created_at": createdAt, "updated_at": updatedAt, "sync_version": syncVersion,
		}, nil
	})
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	favorites, err := snapshotRows(tx, r.Context(), `
		SELECT f.episode_id, f.created_at, f.sync_version,
		       COALESCE((
		           SELECT COALESCE(NULLIF(json_extract(sl.payload_json, '$.added_at'), 0), NULLIF(sl.client_timestamp, 0))
		           FROM sync_log sl
		           WHERE sl.user_id = f.user_id AND sl.entity_type = 'favorite'
		             AND sl.entity_id = f.episode_id
		           ORDER BY sl.server_cursor DESC LIMIT 1
		       ), f.created_at),
		       e.podcast_id, e.guid, e.title, e.description, e.content_encoded,
		       e.pub_date, e.has_pub_date, e.duration_ms, e.enclosure_url,
		       e.enclosure_type, e.enclosure_length, e.artwork_url, e.episode_number,
		       e.season_number, e.episode_type, e.explicit, e.link,
		       p.title, p.artwork_url,
		       COALESCE(
		           (SELECT json_extract(sl.payload_json, '$.categories')
		            FROM sync_log sl
		            WHERE sl.user_id = f.user_id AND sl.entity_type = 'favorite'
		              AND sl.entity_id = f.episode_id
		            ORDER BY sl.server_cursor DESC LIMIT 1),
		           (SELECT ls.categories_json FROM listening_sessions ls
		            WHERE ls.user_id = f.user_id
		              AND (ls.episode_id = e.id OR ls.podcast_id = e.podcast_id)
		            ORDER BY ls.ended_at DESC LIMIT 1),
		           '[]')
		FROM favorites f
		JOIN episodes e ON e.id = f.episode_id
		JOIN podcasts p ON p.id = e.podcast_id
		WHERE f.user_id = ? AND f.is_deleted = 0
		ORDER BY f.episode_id`, authUser.ID, func(rows *sql.Rows) (any, error) {
		var episodeID, podcastID, guid, title, description, contentEncoded string
		var enclosureURL, enclosureType, artworkURL, episodeType, link, podcastTitle, podcastArtworkURL string
		var categoriesJSON string
		var createdAt, syncVersion, addedAt int64
		var pubDate, durationMS, enclosureLength int64
		var hasPubDate, episodeNumber, seasonNumber, explicit int
		if err := rows.Scan(
			&episodeID, &createdAt, &syncVersion, &addedAt,
			&podcastID, &guid, &title, &description, &contentEncoded,
			&pubDate, &hasPubDate, &durationMS, &enclosureURL,
			&enclosureType, &enclosureLength, &artworkURL, &episodeNumber,
			&seasonNumber, &episodeType, &explicit, &link,
			&podcastTitle, &podcastArtworkURL, &categoriesJSON,
		); err != nil {
			return nil, err
		}
		return episodeSnapshotRecord(
			episodeID, podcastID, guid, title, description, contentEncoded,
			pubDate, hasPubDate, durationMS, enclosureURL, enclosureType, enclosureLength,
			artworkURL, episodeNumber, seasonNumber, episodeType, explicit, link,
			podcastTitle, podcastArtworkURL, decodeSnapshotCategories(categoriesJSON),
			map[string]any{"added_at": addedAt, "created_at": createdAt, "sync_version": syncVersion},
		), nil
	})
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	playbackStates, err := snapshotRows(tx, r.Context(), `
		SELECT ps.episode_id, ps.position_ms, ps.completed, ps.progress_percent, ps.event_type,
		       ps.playback_session_id, ps.device_id, ps.per_session_seq, ps.client_timestamp,
		       ps.server_receive_timestamp, ps.sync_version,
		       e.podcast_id, e.guid, e.title, e.description, e.content_encoded,
		       e.pub_date, e.has_pub_date, e.duration_ms, e.enclosure_url,
		       e.enclosure_type, e.enclosure_length, e.artwork_url, e.episode_number,
		       e.season_number, e.episode_type, e.explicit, e.link,
		       p.title, p.artwork_url,
		       COALESCE(
		           (SELECT json_extract(sl.payload_json, '$.categories')
		            FROM sync_log sl
		            WHERE sl.user_id = ps.user_id AND sl.entity_type = 'playback_state'
		              AND sl.entity_id = ps.episode_id
		            ORDER BY sl.server_cursor DESC LIMIT 1),
		           (SELECT ls.categories_json FROM listening_sessions ls
		            WHERE ls.user_id = ps.user_id
		              AND (ls.episode_id = e.id OR ls.podcast_id = e.podcast_id)
		            ORDER BY ls.ended_at DESC LIMIT 1),
		           '[]')
		FROM playback_states ps
		JOIN episodes e ON e.id = ps.episode_id
		JOIN podcasts p ON p.id = e.podcast_id
		WHERE ps.user_id = ?
		ORDER BY ps.episode_id`, authUser.ID, func(rows *sql.Rows) (any, error) {
		var episodeID, eventType, playbackSessionID, deviceID string
		var podcastID, guid, title, description, contentEncoded string
		var enclosureURL, enclosureType, artworkURL, episodeType, link, podcastTitle, podcastArtworkURL string
		var categoriesJSON string
		var positionMS, perSessionSeq, clientTimestamp, serverTimestamp, syncVersion int64
		var pubDate, durationMS, enclosureLength int64
		var completed int
		var hasPubDate, episodeNumber, seasonNumber, explicit int
		var progress float64
		if err := rows.Scan(
			&episodeID, &positionMS, &completed, &progress, &eventType,
			&playbackSessionID, &deviceID, &perSessionSeq, &clientTimestamp,
			&serverTimestamp, &syncVersion,
			&podcastID, &guid, &title, &description, &contentEncoded,
			&pubDate, &hasPubDate, &durationMS, &enclosureURL,
			&enclosureType, &enclosureLength, &artworkURL, &episodeNumber,
			&seasonNumber, &episodeType, &explicit, &link,
			&podcastTitle, &podcastArtworkURL, &categoriesJSON,
		); err != nil {
			return nil, err
		}
		return episodeSnapshotRecord(
			episodeID, podcastID, guid, title, description, contentEncoded,
			pubDate, hasPubDate, durationMS, enclosureURL, enclosureType, enclosureLength,
			artworkURL, episodeNumber, seasonNumber, episodeType, explicit, link,
			podcastTitle, podcastArtworkURL, decodeSnapshotCategories(categoriesJSON),
			map[string]any{
				"position_ms": positionMS, "completed": completed == 1,
				"progress_percent": progress, "event_type": eventType, "playback_session_id": playbackSessionID,
				"device_id": deviceID, "per_session_seq": perSessionSeq, "client_timestamp": clientTimestamp,
				"last_played_at": clientTimestamp, "server_receive_timestamp": serverTimestamp, "sync_version": syncVersion,
			},
		), nil
	})
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	listeningSessions, err := snapshotRows(tx, r.Context(), `
		SELECT id, episode_id, podcast_id, title, podcast_title, categories_json,
		       started_at, ended_at, wall_clock_ms, audio_listened_ms, speed_saved_ms,
		       silence_saved_ms, manual_skipped_ms, intro_outro_skipped_ms,
		       speed_weighted_ms, sync_version
		FROM listening_sessions WHERE user_id = ?
		ORDER BY started_at, id`, authUser.ID, func(rows *sql.Rows) (any, error) {
		var item ListeningSessionPayload
		var categoriesJSON string
		var syncVersion int64
		if err := rows.Scan(
			&item.ID, &item.EpisodeID, &item.PodcastID, &item.Title, &item.PodcastTitle, &categoriesJSON,
			&item.StartedAt, &item.EndedAt, &item.WallClockMS, &item.AudioListenedMS, &item.SpeedSavedMS,
			&item.SilenceSavedMS, &item.ManualSkippedMS, &item.IntroOutroSkippedMS,
			&item.SpeedWeightedMS, &syncVersion,
		); err != nil {
			return nil, err
		}
		if err := json.Unmarshal([]byte(categoriesJSON), &item.Categories); err != nil {
			return nil, err
		}
		return map[string]any{
			"id": item.ID, "episode_id": item.EpisodeID, "podcast_id": item.PodcastID,
			"title": item.Title, "podcast_title": item.PodcastTitle, "categories": item.Categories,
			"started_at": item.StartedAt, "ended_at": item.EndedAt, "wall_clock_ms": item.WallClockMS,
			"audio_listened_ms": item.AudioListenedMS, "speed_saved_ms": item.SpeedSavedMS,
			"silence_saved_ms": item.SilenceSavedMS, "manual_skipped_ms": item.ManualSkippedMS,
			"intro_outro_skipped_ms": item.IntroOutroSkippedMS, "speed_weighted_ms": item.SpeedWeightedMS,
			"sync_version": syncVersion,
		}, nil
	})
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	queueItems, err := latestSyncPayloads(tx, r.Context(), authUser.ID, "queue")
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	podcastSettings, err := latestSyncPayloads(tx, r.Context(), authUser.ID, "podcast_settings")
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	settings, err := latestSyncPayloads(tx, r.Context(), authUser.ID, "settings")
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"failed to complete snapshot"}`, http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"cursor": cursor, "subscriptions": subscriptions, "favorites": favorites,
		"playback_states": playbackStates, "listening_sessions": listeningSessions,
		"queue": queueItems, "podcast_settings": podcastSettings, "settings": settings,
	})
}

// Queue and preference records deliberately live in the append-only sync log.
// They contain denormalized client data and do not need relational server-side
// queries; the newest non-deleted payload per entity is the compact snapshot.
func latestSyncPayloads(
	tx *sql.Tx,
	ctx context.Context,
	userID, entityType string,
) ([]any, error) {
	rows, err := tx.QueryContext(ctx, `
		SELECT current.payload_json
		FROM sync_log current
		WHERE current.user_id = ? AND current.entity_type = ?
		  AND current.server_cursor = (
			  SELECT MAX(candidate.server_cursor)
			  FROM sync_log candidate
			  WHERE candidate.user_id = current.user_id
			    AND candidate.entity_type = current.entity_type
			    AND candidate.entity_id = current.entity_id
		  )
		  AND current.action != 'delete'
		ORDER BY current.entity_id
	`, userID, entityType)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	result := make([]any, 0)
	for rows.Next() {
		var raw string
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var payload any
		if err := json.Unmarshal([]byte(raw), &payload); err != nil {
			return nil, err
		}
		result = append(result, payload)
	}
	return result, rows.Err()
}

func episodeSnapshotRecord(
	episodeID, podcastID, guid, title, description, contentEncoded string,
	pubDate int64, hasPubDate int, durationMS int64,
	enclosureURL, enclosureType string, enclosureLength int64,
	artworkURL string, episodeNumber, seasonNumber int,
	episodeType string, explicit int, link, podcastTitle, podcastArtworkURL string,
	categories []string,
	extra map[string]any,
) map[string]any {
	record := map[string]any{
		"id": episodeID, "episode_id": episodeID, "podcast_id": podcastID,
		"guid": guid, "title": title, "description": description,
		"content_encoded": contentEncoded, "pub_date": pubDate, "has_pub_date": hasPubDate == 1,
		"duration_ms": durationMS, "enclosure_url": enclosureURL, "enclosure_type": enclosureType,
		"enclosure_length": enclosureLength, "artwork_url": artworkURL,
		"episode_number": episodeNumber, "season_number": seasonNumber,
		"episode_type": episodeType, "explicit": explicit == 1, "link": link,
		"podcast_title": podcastTitle, "podcast_artwork_url": podcastArtworkURL,
	}
	if len(categories) > 0 {
		record["categories"] = categories
	}
	for key, value := range extra {
		record[key] = value
	}
	return record
}

func decodeSnapshotCategories(raw string) []string {
	var categories []string
	if err := json.Unmarshal([]byte(raw), &categories); err != nil {
		return nil
	}
	return categories
}

func snapshotRows(tx *sql.Tx, ctx context.Context, query, userID string, scan func(*sql.Rows) (any, error)) ([]any, error) {
	rows, err := tx.QueryContext(ctx, query, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := make([]any, 0)
	for rows.Next() {
		item, err := scan(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func (h *SyncHandler) Push(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	var req SyncPushRequest
	if err := decodeLimitedJSON(w, r, 4*1024*1024, &req); err != nil {
		http.Error(w, `{"error":"invalid sync push payload"}`, http.StatusBadRequest)
		return
	}
	if len(req.Operations) > 500 {
		http.Error(w, `{"error":"at most 500 operations are allowed"}`, http.StatusBadRequest)
		return
	}
	for i := range req.Operations {
		if err := validateSyncOperation(&req.Operations[i]); err != nil {
			http.Error(w, fmt.Sprintf(`{"error":"invalid operation at index %d: %s"}`, i, err.Error()), http.StatusBadRequest)
			return
		}
	}

	ctx := r.Context()
	tx, err := h.DB.SQL.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, `{"error":"transaction error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Ensure the cursor row exists before reading it. Without this a push that
	// happens before the user's first pull would read/update a non-existent row,
	// leaving user_sync_cursors.current_cursor at 0 while sync_log advances — which
	// then makes a later push reuse cursor values and drop changesets from pulls.
	if _, err := tx.ExecContext(ctx, `
		INSERT OR IGNORE INTO user_sync_cursors (user_id, current_cursor, min_retained_cursor, protocol_version, client_schema_version)
		VALUES (?, 0, 0, 1, 1)
	`, authUser.ID); err != nil {
		http.Error(w, `{"error":"failed to initialize sync cursor"}`, http.StatusInternalServerError)
		return
	}

	// Get current user sync cursor
	var currentCursor int64
	if err := tx.QueryRowContext(ctx, "SELECT current_cursor FROM user_sync_cursors WHERE user_id = ?", authUser.ID).Scan(&currentCursor); err != nil {
		http.Error(w, `{"error":"failed to read sync cursor"}`, http.StatusInternalServerError)
		return
	}

	appliedOps := 0
	nowMs := time.Now().UnixMilli()

	for _, op := range req.Operations {
		var existingCursor int64
		err := tx.QueryRowContext(ctx, `
			SELECT server_cursor FROM processed_sync_operations
			WHERE user_id = ? AND device_id = ? AND client_op_id = ?
		`, authUser.ID, op.DeviceID, op.ClientOpID).Scan(&existingCursor)
		if err == nil {
			continue
		}
		if err != sql.ErrNoRows {
			http.Error(w, `{"error":"failed to check operation idempotency"}`, http.StatusInternalServerError)
			return
		}

		nextCursor := currentCursor + 1
		applied, err := h.applyOperation(ctx, tx, authUser.ID, op, nextCursor, nowMs)
		if err != nil {
			http.Error(w, `{"error":"failed to apply sync operation"}`, http.StatusBadRequest)
			return
		}
		if !applied {
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO processed_sync_operations (user_id, device_id, client_op_id, server_cursor, processed_at)
				VALUES (?, ?, ?, ?, ?)
			`, authUser.ID, op.DeviceID, op.ClientOpID, currentCursor, nowMs); err != nil {
				http.Error(w, `{"error":"failed to record rejected operation idempotency"}`, http.StatusInternalServerError)
				return
			}
			continue
		}
		currentCursor = nextCursor

		_, err = tx.ExecContext(ctx, `
			INSERT INTO sync_log (user_id, device_id, client_op_id, entity_type, entity_id, action, payload_json, client_timestamp, server_timestamp, server_cursor)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, authUser.ID, op.DeviceID, op.ClientOpID, op.EntityType, op.EntityID, op.Action, string(op.Payload), op.ClientTimestamp, nowMs, currentCursor)
		if err != nil {
			http.Error(w, `{"error":"failed to append sync log"}`, http.StatusInternalServerError)
			return
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO processed_sync_operations (user_id, device_id, client_op_id, server_cursor, processed_at)
			VALUES (?, ?, ?, ?, ?)
		`, authUser.ID, op.DeviceID, op.ClientOpID, currentCursor, nowMs); err != nil {
			http.Error(w, `{"error":"failed to record operation idempotency"}`, http.StatusInternalServerError)
			return
		}

		appliedOps++
	}

	// Update user's monotonic cursor
	if _, err := tx.ExecContext(ctx, "UPDATE user_sync_cursors SET current_cursor = ? WHERE user_id = ?", currentCursor, authUser.ID); err != nil {
		http.Error(w, `{"error":"failed to update sync cursor"}`, http.StatusInternalServerError)
		return
	}

	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"failed to commit sync operations"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"applied_ops":    appliedOps,
		"current_cursor": currentCursor,
	})
}

func validateSyncOperation(op *SyncPushOperation) error {
	op.ClientOpID = strings.TrimSpace(op.ClientOpID)
	op.DeviceID = strings.TrimSpace(op.DeviceID)
	op.EntityType = strings.TrimSpace(op.EntityType)
	op.EntityID = strings.TrimSpace(op.EntityID)
	op.Action = strings.TrimSpace(op.Action)
	if op.ClientOpID == "" || op.DeviceID == "" || op.EntityID == "" {
		return fmt.Errorf("client_op_id, device_id and entity_id are required")
	}
	if !json.Valid(op.Payload) {
		return fmt.Errorf("payload must be valid JSON")
	}
	switch op.EntityType {
	case "subscription", "favorite":
		if op.Action != "upsert" && op.Action != "delete" {
			return fmt.Errorf("%s action must be upsert or delete", op.EntityType)
		}
	case "playback_state":
		if op.Action != "upsert" {
			return fmt.Errorf("playback_state action must be upsert")
		}
		var p PlaybackStatePayload
		if err := json.Unmarshal(op.Payload, &p); err != nil {
			return fmt.Errorf("invalid playback_state payload")
		}
		if p.EpisodeID == "" || p.EpisodeID != op.EntityID || p.PositionMS < 0 ||
			math.IsNaN(p.ProgressPercent) || math.IsInf(p.ProgressPercent, 0) ||
			p.ProgressPercent < 0 || p.ProgressPercent > 100 || p.PerSessionSeq < 0 {
			return fmt.Errorf("invalid playback_state values")
		}
		switch p.EventType {
		case "PROGRESS_TICK", "SEEK", "RESTART", "MARK_PLAYED", "MARK_UNPLAYED":
		default:
			return fmt.Errorf("invalid playback_state event_type")
		}
	case "listening_session":
		if op.Action != "upsert" {
			return fmt.Errorf("listening_session action must be upsert")
		}
		var p ListeningSessionPayload
		if err := json.Unmarshal(op.Payload, &p); err != nil {
			return fmt.Errorf("invalid listening_session payload")
		}
		if p.ID == "" {
			p.ID = op.EntityID
			encoded, _ := json.Marshal(p)
			op.Payload = encoded
		}
		if p.ID != op.EntityID {
			return fmt.Errorf("listening_session id must match entity_id")
		}
		if err := validateListeningSession(p); err != nil {
			return err
		}
	case "queue":
		if op.Action != "upsert" || op.EntityID != "main" {
			return fmt.Errorf("queue must upsert entity main")
		}
		var payload struct {
			Items     []json.RawMessage `json:"items"`
			UpdatedAt int64             `json:"updated_at"`
		}
		if err := json.Unmarshal(op.Payload, &payload); err != nil ||
			payload.UpdatedAt <= 0 || len(payload.Items) > 500 {
			return fmt.Errorf("invalid queue payload")
		}
	case "settings":
		if op.Action != "upsert" || op.EntityID != "global" {
			return fmt.Errorf("settings must upsert entity global")
		}
		if err := validateObjectPayload(op.Payload, op.ClientTimestamp); err != nil {
			return fmt.Errorf("invalid settings payload")
		}
	case "podcast_settings":
		if op.Action != "upsert" && op.Action != "delete" {
			return fmt.Errorf("podcast_settings action must be upsert or delete")
		}
		if op.Action == "upsert" {
			var payload struct {
				PodcastID string `json:"podcast_id"`
				UpdatedAt int64  `json:"updated_at"`
			}
			if err := json.Unmarshal(op.Payload, &payload); err != nil ||
				payload.PodcastID != op.EntityID || payload.UpdatedAt <= 0 {
				return fmt.Errorf("invalid podcast_settings payload")
			}
		}
	default:
		return fmt.Errorf("unsupported entity_type %q", op.EntityType)
	}
	return nil
}

func validateObjectPayload(payload json.RawMessage, timestamp int64) error {
	if timestamp <= 0 {
		return fmt.Errorf("timestamp must be positive")
	}
	var object map[string]any
	if err := json.Unmarshal(payload, &object); err != nil || object == nil {
		return fmt.Errorf("payload must be an object")
	}
	return nil
}

func validateListeningSession(p ListeningSessionPayload) error {
	if p.ID == "" || p.StartedAt <= 0 || p.EndedAt < p.StartedAt {
		return fmt.Errorf("invalid listening_session timestamps")
	}
	if p.EndedAt-p.StartedAt > maxListeningSessionSpanMS ||
		p.WallClockMS < 0 || p.WallClockMS > maxListeningSessionSpanMS ||
		p.AudioListenedMS < 0 || p.AudioListenedMS > maxListeningSessionSpanMS*4 ||
		p.SpeedSavedMS < 0 || p.SpeedSavedMS > maxListeningSessionSpanMS*4 ||
		p.SilenceSavedMS < 0 || p.SilenceSavedMS > maxListeningSessionSpanMS*4 ||
		p.ManualSkippedMS < 0 || p.ManualSkippedMS > maxListeningSessionSpanMS*4 ||
		p.IntroOutroSkippedMS < 0 || p.IntroOutroSkippedMS > maxListeningSessionSpanMS*4 ||
		p.SpeedWeightedMS < 0 || p.SpeedWeightedMS > maxListeningSessionSpanMS*4 {
		return fmt.Errorf("invalid listening_session duration")
	}
	return nil
}

func (h *SyncHandler) applyOperation(ctx context.Context, tx *sql.Tx, userID string, op SyncPushOperation, syncVer, nowMs int64) (bool, error) {
	switch op.EntityType {
	case "playback_state":
		var p PlaybackStatePayload
		if err := json.Unmarshal(op.Payload, &p); err != nil {
			return false, err
		}
		return h.applyPlaybackState(ctx, tx, userID, p, syncVer, nowMs)
	case "listening_session":
		var p ListeningSessionPayload
		if err := json.Unmarshal(op.Payload, &p); err != nil {
			return false, err
		}
		return h.applyListeningSession(ctx, tx, userID, p, syncVer)
	case "subscription":
		err := h.applySubscription(ctx, tx, userID, op, syncVer, nowMs)
		return err == nil, err
	case "favorite":
		err := h.applyFavorite(ctx, tx, userID, op, syncVer, nowMs)
		return err == nil, err
	case "queue", "settings", "podcast_settings":
		// The append-only sync_log written by Push is the materialized record for
		// these denormalized entities; no second table is required.
		return true, nil
	default:
		return false, fmt.Errorf("unsupported entity type")
	}
}

func (h *SyncHandler) applyListeningSession(ctx context.Context, tx *sql.Tx, userID string, p ListeningSessionPayload, syncVer int64) (bool, error) {
	if err := validateListeningSession(p); err != nil {
		return false, err
	}
	categories, err := json.Marshal(p.Categories)
	if err != nil {
		return false, err
	}
	result, err := tx.ExecContext(ctx, `
		INSERT INTO listening_sessions (
			id, user_id, episode_id, podcast_id, title, podcast_title, categories_json,
			started_at, ended_at, wall_clock_ms, audio_listened_ms, speed_saved_ms,
			silence_saved_ms, manual_skipped_ms, intro_outro_skipped_ms,
			speed_weighted_ms, sync_version
		)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(user_id, id) DO UPDATE SET
			episode_id = excluded.episode_id,
			podcast_id = excluded.podcast_id,
			title = excluded.title,
			podcast_title = excluded.podcast_title,
			categories_json = excluded.categories_json,
			started_at = excluded.started_at,
			ended_at = excluded.ended_at,
			wall_clock_ms = excluded.wall_clock_ms,
			audio_listened_ms = excluded.audio_listened_ms,
			speed_saved_ms = excluded.speed_saved_ms,
			silence_saved_ms = excluded.silence_saved_ms,
			manual_skipped_ms = excluded.manual_skipped_ms,
			intro_outro_skipped_ms = excluded.intro_outro_skipped_ms,
			speed_weighted_ms = excluded.speed_weighted_ms,
			sync_version = excluded.sync_version
		WHERE excluded.ended_at >= listening_sessions.ended_at
	`, p.ID, userID, p.EpisodeID, p.PodcastID, p.Title, p.PodcastTitle, string(categories),
		p.StartedAt, p.EndedAt, max(p.WallClockMS, 0), max(p.AudioListenedMS, 0),
		max(p.SpeedSavedMS, 0), max(p.SilenceSavedMS, 0), max(p.ManualSkippedMS, 0),
		max(p.IntroOutroSkippedMS, 0), max(p.SpeedWeightedMS, 0), syncVer)
	if err != nil {
		return false, err
	}
	rows, err := result.RowsAffected()
	return rows > 0, err
}

func (h *SyncHandler) applyPlaybackState(ctx context.Context, tx *sql.Tx, userID string, p PlaybackStatePayload, syncVer, nowMs int64) (bool, error) {
	// Conflict resolution rules
	var existingPos, existingSeq int64
	var existingCompleted int
	var existingSessionID string
	err := tx.QueryRowContext(ctx, `
		SELECT position_ms, completed, playback_session_id, per_session_seq
		FROM playback_states
		WHERE user_id = ? AND episode_id = ?
	`, userID, p.EpisodeID).Scan(&existingPos, &existingCompleted, &existingSessionID, &existingSeq)

	completedInt := 0
	if p.Completed {
		completedInt = 1
	}

	if err == sql.ErrNoRows {
		// New entry -> Insert immediately
		_, err = tx.ExecContext(ctx, `
			INSERT INTO playback_states (user_id, episode_id, position_ms, completed, progress_percent, event_type, playback_session_id, device_id, per_session_seq, client_timestamp, server_receive_timestamp, sync_version)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, userID, p.EpisodeID, p.PositionMS, completedInt, p.ProgressPercent, p.EventType, p.PlaybackSessionID, p.DeviceID, p.PerSessionSeq, p.ClientTimestamp, nowMs, syncVer)
		return err == nil, err
	}
	if err != nil {
		return false, err
	}

	// 1. Passive Ticks (PROGRESS_TICK): Cannot move position backwards or reopen completed episode
	if p.EventType == "PROGRESS_TICK" {
		if existingCompleted == 1 {
			return false, nil // Cannot reopen completed episode via passive tick
		}
		if p.PlaybackSessionID == existingSessionID && p.PositionMS < existingPos {
			return false, nil // Cannot regress position in same session via passive tick
		}
	}

	// 2. Explicit Actions (SEEK, RESTART, MARK_PLAYED, MARK_UNPLAYED) take immediate precedence
	_, err = tx.ExecContext(ctx, `
		UPDATE playback_states
		SET position_ms = ?,
			completed = ?,
			progress_percent = ?,
			event_type = ?,
			playback_session_id = ?,
			device_id = ?,
			per_session_seq = ?,
			client_timestamp = ?,
			server_receive_timestamp = ?,
			sync_version = ?
		WHERE user_id = ? AND episode_id = ?
	`, p.PositionMS, completedInt, p.ProgressPercent, p.EventType, p.PlaybackSessionID, p.DeviceID, p.PerSessionSeq, p.ClientTimestamp, nowMs, syncVer, userID, p.EpisodeID)
	return err == nil, err
}

func (h *SyncHandler) applySubscription(ctx context.Context, tx *sql.Tx, userID string, op SyncPushOperation, syncVer, nowMs int64) error {
	isDeleted := 0
	if op.Action == "delete" {
		isDeleted = 1
	}

	_, err := tx.ExecContext(ctx, `
		INSERT INTO subscriptions (user_id, podcast_id, created_at, updated_at, is_deleted, sync_version)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(user_id, podcast_id) DO UPDATE SET
			is_deleted = excluded.is_deleted,
			updated_at = excluded.updated_at,
			sync_version = excluded.sync_version
	`, userID, op.EntityID, nowMs, nowMs, isDeleted, syncVer)
	return err
}

func (h *SyncHandler) applyFavorite(ctx context.Context, tx *sql.Tx, userID string, op SyncPushOperation, syncVer, nowMs int64) error {
	isDeleted := 0
	if op.Action == "delete" {
		isDeleted = 1
	}

	_, err := tx.ExecContext(ctx, `
		INSERT INTO favorites (user_id, episode_id, created_at, is_deleted, sync_version)
		VALUES (?, ?, ?, ?, ?)
		ON CONFLICT(user_id, episode_id) DO UPDATE SET
			is_deleted = excluded.is_deleted,
			sync_version = excluded.sync_version
	`, userID, op.EntityID, nowMs, isDeleted, syncVer)
	return err
}

func (h *SyncHandler) MergeLocalData(w http.ResponseWriter, r *http.Request) {
	// Merging local-mode data uses identical apply/dedupe semantics as a normal
	// push. Delegate directly to Push — do NOT pre-decode r.Body here, or Push
	// would receive an already-drained body and fail with an empty-payload error.
	h.Push(w, r)
}

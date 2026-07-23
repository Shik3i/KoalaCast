package handlers

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"
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
	EntityType      string          `json:"entity_type"` // "subscription", "playback_state", "favorite", "queue", "settings"
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
	EpisodeID            string `json:"episode_id"`
	PositionMS           int64  `json:"position_ms"`
	Completed            bool   `json:"completed"`
	ProgressPercent      float64`json:"progress_percent"`
	EventType            string `json:"event_type"` // "PROGRESS_TICK", "SEEK", "RESTART", "MARK_PLAYED", "MARK_UNPLAYED"
	PlaybackSessionID    string `json:"playback_session_id"`
	DeviceID             string `json:"device_id"`
	PerSessionSeq        int64  `json:"per_session_seq"`
	ClientTimestamp      int64  `json:"client_timestamp"`
}

type QueueOperationPayload struct {
	OpType          string `json:"op_type"` // "ADD_AFTER", "ADD_TO_BEGINNING", "ADD_TO_END", "REMOVE_ITEM", "MOVE_AFTER", "CLEAR_QUEUE"
	ItemID          string `json:"item_id"`
	EpisodeID       string `json:"episode_id"`
	ReferenceItemID string `json:"reference_item_id"`
}

func (h *SyncHandler) Pull(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	sinceCursorStr := r.URL.Query().Get("since_cursor")
	sinceCursor := int64(0)
	if sc, err := strconv.ParseInt(sinceCursorStr, 10, 64); err == nil && sc >= 0 {
		sinceCursor = sc
	}

	// Check if client cursor is older than min_retained_cursor
	var currentCursor, minRetainedCursor int64
	err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT current_cursor, min_retained_cursor FROM user_sync_cursors WHERE user_id = ?
	`, authUser.ID).Scan(&currentCursor, &minRetainedCursor)
	if err == sql.ErrNoRows {
		// Initialize cursor for user
		_, _ = h.DB.SQL.ExecContext(r.Context(), "INSERT INTO user_sync_cursors (user_id, current_cursor, min_retained_cursor, protocol_version, client_schema_version) VALUES (?, 0, 0, 1, 1)", authUser.ID)
		currentCursor = 0
		minRetainedCursor = 0
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
		LIMIT 500
	`, authUser.ID, sinceCursor)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type LogEntry struct {
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

	changesets := make([]LogEntry, 0)
	for rows.Next() {
		var item LogEntry
		var payloadStr string
		if err := rows.Scan(&item.ID, &item.DeviceID, &item.ClientOpID, &item.EntityType, &item.EntityID, &item.Action, &payloadStr, &item.ClientTimestamp, &item.ServerTimestamp, &item.ServerCursor); err == nil {
			item.Payload = json.RawMessage(payloadStr)
			changesets = append(changesets, item)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"since_cursor":   sinceCursor,
		"current_cursor": currentCursor,
		"changesets":     changesets,
	})
}

func (h *SyncHandler) Push(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	var req SyncPushRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid sync push payload"}`, http.StatusBadRequest)
		return
	}

	ctx := r.Context()
	tx, err := h.DB.SQL.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, `{"error":"transaction error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Get current user sync cursor
	var currentCursor int64
	_ = tx.QueryRowContext(ctx, "SELECT current_cursor FROM user_sync_cursors WHERE user_id = ?", authUser.ID).Scan(&currentCursor)

	appliedOps := 0
	nowMs := time.Now().UnixMilli()

	for _, op := range req.Operations {
		if op.ClientOpID == "" || op.EntityType == "" {
			continue
		}

		// Deduplication Check via UNIQUE(user_id, device_id, client_op_id)
		var existingID int64
		err := tx.QueryRowContext(ctx, "SELECT id FROM sync_log WHERE user_id = ? AND device_id = ? AND client_op_id = ?", authUser.ID, op.DeviceID, op.ClientOpID).Scan(&existingID)
		if err == nil {
			// Already processed -> Skip re-execution
			continue
		}

		currentCursor++

		// Apply state mutation to materialized tables
		if op.EntityType == "playback_state" {
			var p PlaybackStatePayload
			if err := json.Unmarshal(op.Payload, &p); err == nil {
				h.applyPlaybackState(ctx, tx, authUser.ID, p, currentCursor, nowMs)
			}
		} else if op.EntityType == "subscription" {
			h.applySubscription(ctx, tx, authUser.ID, op, currentCursor, nowMs)
		} else if op.EntityType == "favorite" {
			h.applyFavorite(ctx, tx, authUser.ID, op, currentCursor, nowMs)
		}

		// Append to sync_log
		payloadBytes, _ := json.Marshal(op.Payload)
		_, err = tx.ExecContext(ctx, `
			INSERT INTO sync_log (user_id, device_id, client_op_id, entity_type, entity_id, action, payload_json, client_timestamp, server_timestamp, server_cursor)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, authUser.ID, op.DeviceID, op.ClientOpID, op.EntityType, op.EntityID, op.Action, string(payloadBytes), op.ClientTimestamp, nowMs, currentCursor)
		if err != nil {
			// Deduplication collision
			continue
		}

		appliedOps++
	}

	// Update user's monotonic cursor
	_, _ = tx.ExecContext(ctx, "UPDATE user_sync_cursors SET current_cursor = ? WHERE user_id = ?", currentCursor, authUser.ID)

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

func (h *SyncHandler) applyPlaybackState(ctx context.Context, tx *sql.Tx, userID string, p PlaybackStatePayload, syncVer, nowMs int64) {
	// Conflict resolution rules
	var existingPos, existingSeq int64
	var existingCompleted, existingSessionID string
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
		_, _ = tx.ExecContext(ctx, `
			INSERT INTO playback_states (user_id, episode_id, position_ms, completed, progress_percent, event_type, playback_session_id, device_id, per_session_seq, client_timestamp, server_receive_timestamp, sync_version)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, userID, p.EpisodeID, p.PositionMS, completedInt, p.ProgressPercent, p.EventType, p.PlaybackSessionID, p.DeviceID, p.PerSessionSeq, p.ClientTimestamp, nowMs, syncVer)
		return
	}

	// 1. Passive Ticks (PROGRESS_TICK): Cannot move position backwards or reopen completed episode
	if p.EventType == "PROGRESS_TICK" {
		if existingCompleted == "1" {
			return // Cannot reopen completed episode via passive tick
		}
		if p.PlaybackSessionID == existingSessionID && p.PositionMS < existingPos {
			return // Cannot regress position in same session via passive tick
		}
	}

	// 2. Explicit Actions (SEEK, RESTART, MARK_PLAYED, MARK_UNPLAYED) take immediate precedence
	_, _ = tx.ExecContext(ctx, `
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
}

func (h *SyncHandler) applySubscription(ctx context.Context, tx *sql.Tx, userID string, op SyncPushOperation, syncVer, nowMs int64) {
	isDeleted := 0
	if op.Action == "delete" {
		isDeleted = 1
	}

	_, _ = tx.ExecContext(ctx, `
		INSERT INTO subscriptions (user_id, podcast_id, created_at, updated_at, is_deleted, sync_version)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(user_id, podcast_id) DO UPDATE SET
			is_deleted = excluded.is_deleted,
			updated_at = excluded.updated_at,
			sync_version = excluded.sync_version
	`, userID, op.EntityID, nowMs, nowMs, isDeleted, syncVer)
}

func (h *SyncHandler) applyFavorite(ctx context.Context, tx *sql.Tx, userID string, op SyncPushOperation, syncVer, nowMs int64) {
	isDeleted := 0
	if op.Action == "delete" {
		isDeleted = 1
	}

	_, _ = tx.ExecContext(ctx, `
		INSERT INTO favorites (user_id, episode_id, created_at, is_deleted, sync_version)
		VALUES (?, ?, ?, ?, ?)
		ON CONFLICT(user_id, episode_id) DO UPDATE SET
			is_deleted = excluded.is_deleted,
			sync_version = excluded.sync_version
	`, userID, op.EntityID, nowMs, isDeleted, syncVer)
}

func (h *SyncHandler) MergeLocalData(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	var req SyncPushRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid merge payload"}`, http.StatusBadRequest)
		return
	}

	// Execute Push logic for all local mode data
	h.Push(w, r)
}

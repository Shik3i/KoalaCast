package handlers

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
	"github.com/Shik3i/KoalaCast/services/api/internal/worker"
	"github.com/go-chi/chi/v5"
)

type AdminHandler struct {
	DB     *db.DB
	Config *config.Config
	Worker *worker.FeedWorker
}

func (h *AdminHandler) RequireAdmin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authUser := customMiddleware.GetAuthUser(r.Context())
		if authUser == nil || authUser.Role != "admin" {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusForbidden)
			_, _ = w.Write([]byte(`{"error":"admin access required"}`))
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (h *AdminHandler) ListUsers(w http.ResponseWriter, r *http.Request) {
	rows, err := h.DB.SQL.QueryContext(r.Context(), `
		SELECT id, username, role, is_suspended, created_at,
		       (SELECT COUNT(*) FROM sessions WHERE user_id = u.id AND expires_at > ?) as active_sessions
		FROM users u
		ORDER BY created_at DESC
	`, time.Now().UnixMilli())
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type UserItem struct {
		ID             string `json:"id"`
		Username       string `json:"username"`
		Role           string `json:"role"`
		IsSuspended    bool   `json:"is_suspended"`
		CreatedAt      int64  `json:"created_at"`
		ActiveSessions int    `json:"active_sessions"`
	}

	users := make([]UserItem, 0)
	for rows.Next() {
		var item UserItem
		var isSuspendedInt int
		if err := rows.Scan(&item.ID, &item.Username, &item.Role, &isSuspendedInt, &item.CreatedAt, &item.ActiveSessions); err == nil {
			item.IsSuspended = (isSuspendedInt == 1)
			users = append(users, item)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"users": users,
		"count": len(users),
	})
}

func (h *AdminHandler) ToggleRegistration(w http.ResponseWriter, r *http.Request) {
	if h.Config.RegistrationEnabledEnv != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(map[string]string{
			"error": "Registration is permanently overridden by environment variable KC_REGISTRATION_ENABLED and cannot be changed via Admin UI",
		})
		return
	}

	var req struct {
		Enabled bool `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid payload"}`, http.StatusBadRequest)
		return
	}

	valStr := "true"
	if !req.Enabled {
		valStr = "false"
	}

	nowMs := time.Now().UnixMilli()
	_, err := h.DB.SQL.ExecContext(r.Context(), `
		INSERT INTO app_settings (key, value, updated_at) VALUES ('registration_enabled', ?, ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
	`, valStr, nowMs)
	if err != nil {
		http.Error(w, `{"error":"failed to save setting"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"registration_enabled": req.Enabled,
	})
}

func (h *AdminHandler) SuspendUser(w http.ResponseWriter, r *http.Request) {
	targetID := chi.URLParam(r, "id")
	authUser := customMiddleware.GetAuthUser(r.Context())

	var req struct {
		Suspend bool `json:"suspend"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, `{"error":"invalid payload"}`, http.StatusBadRequest)
		return
	}

	var targetRole string
	var targetSuspended int
	err := h.DB.SQL.QueryRowContext(r.Context(),
		"SELECT role, is_suspended FROM users WHERE id = ?", targetID).Scan(&targetRole, &targetSuspended)
	if err == sql.ErrNoRows {
		http.Error(w, `{"error":"user not found"}`, http.StatusNotFound)
		return
	} else if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	if req.Suspend {
		// Guard against an admin locking themselves out, or removing the last admin.
		if authUser != nil && targetID == authUser.ID {
			http.Error(w, `{"error":"you cannot suspend your own account"}`, http.StatusBadRequest)
			return
		}
		if targetRole == "admin" {
			var activeAdmins int
			_ = h.DB.SQL.QueryRowContext(r.Context(),
				"SELECT COUNT(*) FROM users WHERE role = 'admin' AND is_suspended = 0").Scan(&activeAdmins)
			if activeAdmins <= 1 {
				http.Error(w, `{"error":"cannot suspend the last active admin"}`, http.StatusBadRequest)
				return
			}
		}
	}

	suspendInt := 0
	if req.Suspend {
		suspendInt = 1
	}

	tx, err := h.DB.SQL.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"failed to begin user status update"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(r.Context(), "UPDATE users SET is_suspended = ? WHERE id = ?", suspendInt, targetID); err != nil {
		http.Error(w, `{"error":"failed to update user status"}`, http.StatusInternalServerError)
		return
	}

	if req.Suspend {
		if _, err := tx.ExecContext(r.Context(), "DELETE FROM sessions WHERE user_id = ?", targetID); err != nil {
			http.Error(w, `{"error":"failed to revoke web sessions"}`, http.StatusInternalServerError)
			return
		}
		if _, err := tx.ExecContext(r.Context(), "DELETE FROM device_credentials WHERE user_id = ?", targetID); err != nil {
			http.Error(w, `{"error":"failed to revoke device credentials"}`, http.StatusInternalServerError)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"failed to commit user status"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"user_id":      targetID,
		"is_suspended": req.Suspend,
	})
}

func (h *AdminHandler) RevokeUserSessions(w http.ResponseWriter, r *http.Request) {
	targetID := chi.URLParam(r, "id")

	res, err := h.DB.SQL.ExecContext(r.Context(), "DELETE FROM sessions WHERE user_id = ?", targetID)
	if err != nil {
		http.Error(w, `{"error":"failed to revoke sessions"}`, http.StatusInternalServerError)
		return
	}

	count, _ := res.RowsAffected()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"user_id":          targetID,
		"revoked_sessions": count,
	})
}

func (h *AdminHandler) FeedHealth(w http.ResponseWriter, r *http.Request) {
	rows, err := h.DB.SQL.QueryContext(r.Context(), `
		SELECT id, title, feed_url, consecutive_error_count, last_error_category, last_http_status, last_fetch_attempt_at, last_successful_fetch_at
		FROM podcasts
		ORDER BY consecutive_error_count DESC, last_fetch_attempt_at DESC
		LIMIT 100
	`)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type FeedHealthItem struct {
		ID                    string `json:"id"`
		Title                 string `json:"title"`
		FeedURL               string `json:"feed_url"`
		ConsecutiveErrors     int    `json:"consecutive_errors"`
		LastErrorCategory     string `json:"last_error_category"`
		LastHTTPStatus        int    `json:"last_http_status"`
		LastFetchAttemptAt    int64  `json:"last_fetch_attempt_at"`
		LastSuccessfulFetchAt int64  `json:"last_successful_fetch_at"`
	}

	feeds := make([]FeedHealthItem, 0)
	for rows.Next() {
		var item FeedHealthItem
		if err := rows.Scan(&item.ID, &item.Title, &item.FeedURL, &item.ConsecutiveErrors, &item.LastErrorCategory, &item.LastHTTPStatus, &item.LastFetchAttemptAt, &item.LastSuccessfulFetchAt); err == nil {
			feeds = append(feeds, item)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"feeds": feeds,
	})
}

func (h *AdminHandler) ManualRefreshFeed(w http.ResponseWriter, r *http.Request) {
	podcastID := chi.URLParam(r, "id")

	// A synchronous feed fetch + parse can exceed the default 15s WriteTimeout for a
	// slow upstream; extend the write deadline for this response.
	if rc := http.NewResponseController(w); rc != nil {
		_ = rc.SetWriteDeadline(time.Now().Add(1 * time.Minute))
	}

	var feedURL, etag, lastModified string
	err := h.DB.SQL.QueryRowContext(r.Context(), "SELECT feed_url, etag, last_modified FROM podcasts WHERE id = ?", podcastID).Scan(&feedURL, &etag, &lastModified)
	if err == sql.ErrNoRows {
		http.Error(w, `{"error":"podcast not found"}`, http.StatusNotFound)
		return
	}

	err = h.Worker.RefreshSingleFeed(r.Context(), podcastID, feedURL, etag, lastModified)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": "feed refresh failed: " + err.Error()})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{"message": "feed refreshed successfully"})
}

func (h *AdminHandler) SystemStatus(w http.ResponseWriter, r *http.Request) {
	var userCount, podcastCount, episodeCount int
	_ = h.DB.SQL.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM users").Scan(&userCount)
	_ = h.DB.SQL.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM podcasts").Scan(&podcastCount)
	_ = h.DB.SQL.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM episodes").Scan(&episodeCount)

	// Database File Size
	var dbMainSizeBytes, dbWALSizeBytes int64
	if fi, err := os.Stat(h.Config.DatabasePath); err == nil {
		dbMainSizeBytes = fi.Size()
	}
	if fi, err := os.Stat(h.Config.DatabasePath + "-wal"); err == nil {
		dbWALSizeBytes = fi.Size()
	}
	dbSizeBytes := dbMainSizeBytes + dbWALSizeBytes

	var episodePayloadBytes, podcastPayloadBytes int64
	_ = h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT COALESCE(SUM(
			LENGTH(title) + LENGTH(description) + LENGTH(content_encoded) +
			LENGTH(enclosure_url) + LENGTH(artwork_url) + LENGTH(transcripts) +
			LENGTH(chapters_url)
		), 0)
		FROM episodes
	`).Scan(&episodePayloadBytes)
	_ = h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT COALESCE(SUM(
			LENGTH(title) + LENGTH(description) + LENGTH(author) +
			LENGTH(artwork_url) + LENGTH(feed_url)
		), 0)
		FROM podcasts
	`).Scan(&podcastPayloadBytes)

	var maxEpisodesPerPodcast, notificationFeedCount int
	_ = h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT COALESCE(MAX(episode_count), 0)
		FROM (SELECT COUNT(*) AS episode_count FROM episodes GROUP BY podcast_id)
	`).Scan(&maxEpisodesPerPodcast)
	_ = h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT COUNT(DISTINCT p.id)
		FROM podcasts p
		WHERE EXISTS (
			SELECT 1
			FROM subscriptions s
			WHERE s.podcast_id = p.id
			  AND s.is_deleted = 0
			  AND COALESCE((
				SELECT CASE
					WHEN sl.action = 'upsert'
					THEN COALESCE(
						json_extract(sl.payload_json, '$.notify_new_episodes'),
						json_extract(sl.payload_json, '$.notifyNewEpisodes'),
						0
					)
					ELSE 0
				END
				FROM sync_log sl
				WHERE sl.user_id = s.user_id
				  AND sl.entity_type = 'podcast_settings'
				  AND sl.entity_id = p.id
				ORDER BY sl.server_cursor DESC
				LIMIT 1
			  ), 0) = 1
		)
	`).Scan(&notificationFeedCount)

	// Registration Effective Status. `registration_locked` means an environment
	// override is in force and the DB toggle is ignored (so the UI disables it).
	regEnabledEnv := "unset (using DB setting)"
	regLocked := h.Config.RegistrationEnabledEnv != nil
	regEnabled := true
	if regLocked {
		regEnabled = *h.Config.RegistrationEnabledEnv
		if regEnabled {
			regEnabledEnv = "enforced true"
		} else {
			regEnabledEnv = "enforced false"
		}
	} else {
		var v string
		if err := h.DB.SQL.QueryRowContext(r.Context(),
			"SELECT value FROM app_settings WHERE key = 'registration_enabled'").Scan(&v); err == nil && v == "false" {
			regEnabled = false
		}
	}

	// Read worker metrics through a mutex-guarded snapshot to avoid a data race
	// with the worker goroutines that update these counters.
	metrics := h.Worker.Snapshot()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"version":                       "1.0.0",
		"database_path":                 filepath.Base(h.Config.DatabasePath),
		"database_size_bytes":           dbSizeBytes,
		"database_main_size_bytes":      dbMainSizeBytes,
		"database_wal_size_bytes":       dbWALSizeBytes,
		"episode_payload_bytes":         episodePayloadBytes,
		"podcast_payload_bytes":         podcastPayloadBytes,
		"user_count":                    userCount,
		"podcast_count":                 podcastCount,
		"episode_count":                 episodeCount,
		"max_episodes_per_podcast":      maxEpisodesPerPodcast,
		"episode_retention_limit":       config.EffectiveFeedMaxStoredEpisodes(h.Config.FeedMaxStoredEpisodes),
		"notification_feed_count":       notificationFeedCount,
		"worker_running":                metrics.IsWorkerRunning,
		"worker_last_run":               metrics.LastRunAt,
		"worker_success_count":          metrics.SuccessCount,
		"worker_failure_count":          metrics.FailureCount,
		"registration_enabled_override": regEnabledEnv,
		"registration_enabled":          regEnabled,
		"registration_locked":           regLocked,
	})
}

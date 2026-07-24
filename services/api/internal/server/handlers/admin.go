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

	var req struct {
		Suspend bool `json:"suspend"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)

	suspendInt := 0
	if req.Suspend {
		suspendInt = 1
	}

	_, err := h.DB.SQL.ExecContext(r.Context(), "UPDATE users SET is_suspended = ? WHERE id = ?", suspendInt, targetID)
	if err != nil {
		http.Error(w, `{"error":"failed to update user status"}`, http.StatusInternalServerError)
		return
	}

	if req.Suspend {
		// Revoke all sessions upon suspension
		_, _ = h.DB.SQL.ExecContext(r.Context(), "DELETE FROM sessions WHERE user_id = ?", targetID)
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
	var dbSizeBytes int64
	if fi, err := os.Stat(h.Config.DatabasePath); err == nil {
		dbSizeBytes = fi.Size()
	}
	// Add WAL file size if present
	if fi, err := os.Stat(h.Config.DatabasePath + "-wal"); err == nil {
		dbSizeBytes += fi.Size()
	}

	// Registration Effective Status
	regEnabledEnv := "unset (using DB setting)"
	if h.Config.RegistrationEnabledEnv != nil {
		if *h.Config.RegistrationEnabledEnv {
			regEnabledEnv = "enforced true"
		} else {
			regEnabledEnv = "enforced false"
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
		"user_count":                    userCount,
		"podcast_count":                 podcastCount,
		"episode_count":                 episodeCount,
		"worker_running":                metrics.IsWorkerRunning,
		"worker_last_run":               metrics.LastRunAt,
		"worker_success_count":          metrics.SuccessCount,
		"worker_failure_count":          metrics.FailureCount,
		"registration_enabled_override": regEnabledEnv,
	})
}

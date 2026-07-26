package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
)

func TestGlobalStatsRequireExplicitOptIn(t *testing.T) {
	database, handler := newGlobalStatsTestHandler(t)
	now := time.Date(2026, 7, 26, 12, 30, 0, 0, time.UTC).UnixMilli()
	seedGlobalStatsUser(t, database, "private", "PrivateUser", false, now)
	seedGlobalStatsUser(t, database, "public", "PublicUser", true, now)
	seedGlobalSession(t, database, "private", "private-session", "private-podcast", "Private Show", now, 60_000)
	seedGlobalSession(t, database, "public", "public-session", "public-podcast", "Public Show", now, 120_000)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/stats/global?range=all", nil)
	rec := httptest.NewRecorder()
	handler.Global(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("global status=%d body=%s", rec.Code, rec.Body.String())
	}

	var result struct {
		Participants int   `json:"participants"`
		TotalWallMS  int64 `json:"total_wall_ms"`
		Podcasts     []struct {
			Title string `json:"title"`
		} `json:"podcast_rankings"`
		Leaderboard []struct {
			Username string `json:"username"`
		} `json:"listener_leaderboard"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&result); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if result.Participants != 1 || result.TotalWallMS != 120_000 {
		t.Fatalf("private data leaked into aggregate: %+v", result)
	}
	if len(result.Podcasts) != 1 || result.Podcasts[0].Title != "Public Show" {
		t.Fatalf("unexpected podcast ranking: %+v", result.Podcasts)
	}
	if len(result.Leaderboard) != 1 || result.Leaderboard[0].Username != "PublicUser" {
		t.Fatalf("unexpected leaderboard: %+v", result.Leaderboard)
	}
}

func TestGlobalStatsPreferenceOptOutRemovesUserImmediately(t *testing.T) {
	database, handler := newGlobalStatsTestHandler(t)
	now := time.Now().UnixMilli()
	seedGlobalStatsUser(t, database, "user-1", "Listener", true, now)
	seedGlobalSession(t, database, "user-1", "session-1", "podcast-1", "Show", now, 90_000)

	authCtx := context.WithValue(context.Background(), customMiddleware.UserContextKey, &customMiddleware.AuthUser{ID: "user-1", Username: "Listener"})
	req := httptest.NewRequest(http.MethodPut, "/api/v1/stats/preferences", bytes.NewBufferString(`{"global_stats_opt_in":false}`)).WithContext(authCtx)
	rec := httptest.NewRecorder()
	handler.UpdatePreference(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("preference status=%d body=%s", rec.Code, rec.Body.String())
	}

	globalReq := httptest.NewRequest(http.MethodGet, "/api/v1/stats/global?range=all", nil)
	globalRec := httptest.NewRecorder()
	handler.Global(globalRec, globalReq)
	var result struct {
		Participants int   `json:"participants"`
		TotalWallMS  int64 `json:"total_wall_ms"`
	}
	if err := json.NewDecoder(globalRec.Body).Decode(&result); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if result.Participants != 0 || result.TotalWallMS != 0 {
		t.Fatalf("opted-out user still visible: %+v", result)
	}

	var storedSessions int
	if err := database.SQL.QueryRow("SELECT COUNT(*) FROM listening_sessions WHERE user_id = 'user-1'").Scan(&storedSessions); err != nil {
		t.Fatalf("stored sessions: %v", err)
	}
	if storedSessions != 1 {
		t.Fatalf("opt-out deleted personal statistics: got %d sessions", storedSessions)
	}
}

func newGlobalStatsTestHandler(t *testing.T) (*db.DB, *GlobalStatsHandler) {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "global-stats.db"), logger)
	if err != nil {
		t.Fatalf("OpenDB: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })
	return database, &GlobalStatsHandler{DB: database}
}

func seedGlobalStatsUser(t *testing.T, database *db.DB, id, username string, optIn bool, now int64) {
	t.Helper()
	enabled := 0
	if optIn {
		enabled = 1
	}
	if _, err := database.SQL.Exec(`
		INSERT INTO users (
			id, username, normalized_username, password_hash, recovery_code_hash,
			global_stats_opt_in, global_stats_opt_in_at, created_at, updated_at
		) VALUES (?, ?, ?, 'hash', 'recovery', ?, ?, ?, ?)
	`, id, username, id, enabled, now, now, now); err != nil {
		t.Fatalf("seed user: %v", err)
	}
}

func seedGlobalSession(t *testing.T, database *db.DB, userID, id, podcastID, podcastTitle string, startedAt, wallMS int64) {
	t.Helper()
	if _, err := database.SQL.Exec(`
		INSERT INTO listening_sessions (
			id, user_id, episode_id, podcast_id, title, podcast_title, categories_json,
			started_at, ended_at, wall_clock_ms, audio_listened_ms,
			speed_saved_ms, speed_weighted_ms
		) VALUES (?, ?, ?, ?, 'Episode', ?, '["Technology"]', ?, ?, ?, ?, ?, ?)
	`, id, userID, id+"-episode", podcastID, podcastTitle, startedAt, startedAt+wallMS, wallMS, wallMS+10_000, 10_000, wallMS); err != nil {
		t.Fatalf("seed session: %v", err)
	}
}

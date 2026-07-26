package handlers

import (
	"context"
	"io/ioutil"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
)

func TestSyncHandler_PlaybackConflictResolution(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_sync_test_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	dbPath := filepath.Join(tempDir, "test.db")
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))

	database, err := db.OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	syncHandler := &SyncHandler{DB: database}
	ctx := context.Background()

	// Seed user & episode
	userID := "user-123"
	podcastID := "pod-123"
	episodeID := "ep-123"
	nowMs := time.Now().UnixMilli()

	_, err = database.SQL.ExecContext(ctx, "INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at) VALUES (?, 'user1', 'user1', 'hash', 'rec', ?, ?)", userID, nowMs, nowMs)
	if err != nil {
		t.Fatalf("failed to insert user: %v", err)
	}

	_, err = database.SQL.ExecContext(ctx, "INSERT INTO podcasts (id, feed_url, title, created_at, updated_at) VALUES (?, 'http://example.com/rss', 'Pod', ?, ?)", podcastID, nowMs, nowMs)
	if err != nil {
		t.Fatalf("failed to insert podcast: %v", err)
	}

	_, err = database.SQL.ExecContext(ctx, "INSERT INTO episodes (id, podcast_id, stable_identity_key, title, enclosure_url, created_at) VALUES (?, ?, 'key1', 'Ep 1', 'http://example.com/ep1.mp3', ?)", episodeID, podcastID, nowMs)
	if err != nil {
		t.Fatalf("failed to insert episode: %v", err)
	}

	tx, _ := database.SQL.BeginTx(ctx, nil)

	// Step 1: Initial progress tick at 30,000 ms (30 seconds)
	p1 := PlaybackStatePayload{
		EpisodeID:         episodeID,
		PositionMS:        30000,
		Completed:         false,
		ProgressPercent:   10.0,
		EventType:         "PROGRESS_TICK",
		PlaybackSessionID: "sess-1",
		DeviceID:          "dev-1",
		PerSessionSeq:     1,
		ClientTimestamp:   nowMs,
	}
	syncHandler.applyPlaybackState(ctx, tx, userID, p1, 1, nowMs)

	var pos1 int64
	var comp1 int
	_ = tx.QueryRowContext(ctx, "SELECT position_ms, completed FROM playback_states WHERE user_id = ? AND episode_id = ?", userID, episodeID).Scan(&pos1, &comp1)
	if pos1 != 30000 || comp1 != 0 {
		t.Errorf("expected pos 30000, got %d, comp %d", pos1, comp1)
	}

	// Step 2: Stale passive tick arriving with earlier position (15,000 ms) -> Must be rejected
	p2 := PlaybackStatePayload{
		EpisodeID:         episodeID,
		PositionMS:        15000,
		Completed:         false,
		ProgressPercent:   5.0,
		EventType:         "PROGRESS_TICK",
		PlaybackSessionID: "sess-1",
		DeviceID:          "dev-1",
		PerSessionSeq:     2,
		ClientTimestamp:   nowMs + 100,
	}
	syncHandler.applyPlaybackState(ctx, tx, userID, p2, 2, nowMs+100)

	var pos2 int64
	_ = tx.QueryRowContext(ctx, "SELECT position_ms FROM playback_states WHERE user_id = ? AND episode_id = ?", userID, episodeID).Scan(&pos2)
	if pos2 != 30000 {
		t.Errorf("expected position to stay 30000 after stale passive tick, got %d", pos2)
	}

	// Step 3: Explicit SEEK backwards (to 10,000 ms) -> Must be accepted
	p3 := PlaybackStatePayload{
		EpisodeID:         episodeID,
		PositionMS:        10000,
		Completed:         false,
		ProgressPercent:   3.3,
		EventType:         "SEEK",
		PlaybackSessionID: "sess-1",
		DeviceID:          "dev-1",
		PerSessionSeq:     3,
		ClientTimestamp:   nowMs + 200,
	}
	syncHandler.applyPlaybackState(ctx, tx, userID, p3, 3, nowMs+200)

	var pos3 int64
	_ = tx.QueryRowContext(ctx, "SELECT position_ms FROM playback_states WHERE user_id = ? AND episode_id = ?", userID, episodeID).Scan(&pos3)
	if pos3 != 10000 {
		t.Errorf("expected position to update to 10000 after explicit SEEK, got %d", pos3)
	}

	_ = tx.Rollback()
}

func TestSyncHandler_ListeningSessionUpsertKeepsNewestAggregate(t *testing.T) {
	tempDir := t.TempDir()
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(tempDir, "listening.db"), logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	ctx := context.Background()
	nowMs := time.Now().UnixMilli()
	if _, err := database.SQL.ExecContext(ctx, `
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at)
		VALUES ('u-listen', 'listener', 'listener', 'hash', 'recovery', ?, ?)
	`, nowMs, nowMs); err != nil {
		t.Fatalf("seed user: %v", err)
	}

	handler := &SyncHandler{DB: database}
	tx, err := database.SQL.BeginTx(ctx, nil)
	if err != nil {
		t.Fatalf("begin: %v", err)
	}
	base := ListeningSessionPayload{
		ID: "session-1", EpisodeID: "episode-1", PodcastID: "podcast-1",
		Title: "Episode", PodcastTitle: "Podcast", Categories: []string{"Technology"},
		StartedAt: nowMs, EndedAt: nowMs + 60_000, WallClockMS: 60_000,
		AudioListenedMS: 75_000, SpeedSavedMS: 15_000, SpeedWeightedMS: 75_000,
	}
	handler.applyListeningSession(ctx, tx, "u-listen", base, 1)
	stale := base
	stale.EndedAt = nowMs + 30_000
	stale.WallClockMS = 30_000
	handler.applyListeningSession(ctx, tx, "u-listen", stale, 2)

	var wallMs, syncVersion int64
	if err := tx.QueryRowContext(ctx, `
		SELECT wall_clock_ms, sync_version FROM listening_sessions WHERE user_id = 'u-listen' AND id = 'session-1'
	`).Scan(&wallMs, &syncVersion); err != nil {
		t.Fatalf("read session: %v", err)
	}
	if wallMs != 60_000 || syncVersion != 1 {
		t.Fatalf("stale aggregate overwrote newest: wall=%d sync=%d", wallMs, syncVersion)
	}
	_ = tx.Rollback()
}

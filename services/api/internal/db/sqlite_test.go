package db

import (
	"database/sql"
	"io/ioutil"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestOpenDB_Migrations(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koalacast_test_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	dbPath := filepath.Join(tempDir, "test.db")
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))

	db, err := OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer db.Close()

	// Verify tables were created by migration
	tables := []string{"users", "sessions", "podcasts", "episodes", "subscriptions", "playback_states", "listening_sessions", "sync_log", "error_events"}
	for _, table := range tables {
		var name string
		err := db.SQL.QueryRow("SELECT name FROM sqlite_master WHERE type='table' AND name=?", table).Scan(&name)
		if err != nil {
			t.Errorf("expected table %s to exist, error: %v", table, err)
		}
	}
}

func TestErrorEventsRetentionRemovesEntriesOlderThanSevenDays(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := OpenDB(filepath.Join(t.TempDir(), "error-retention.db"), logger)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	old := time.Now().Add(-8 * 24 * time.Hour).UnixMilli()
	now := time.Now().UnixMilli()
	if _, err := database.SQL.Exec(`
		INSERT INTO error_events (occurred_at, status_code, method, path, message)
		VALUES (?, 500, 'GET', '/api/v1/old', 'old')
	`, old); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 249; i++ {
		if _, err := database.SQL.Exec(`
			INSERT INTO error_events (occurred_at, status_code, method, path, message)
			VALUES (?, 400, 'GET', '/api/v1/current', 'current')
		`, now); err != nil {
			t.Fatal(err)
		}
	}

	var oldCount int
	if err := database.SQL.QueryRow("SELECT COUNT(*) FROM error_events WHERE path = '/api/v1/old'").Scan(&oldCount); err != nil {
		t.Fatal(err)
	}
	if oldCount != 0 {
		t.Fatalf("expected seven-day retention trigger to remove old error, count=%d", oldCount)
	}
}

func TestOpenDB_RepairsPartialGlobalStatisticsMigration(t *testing.T) {
	registerDriver()
	dbPath := filepath.Join(t.TempDir(), "partial.db")
	raw, err := sql.Open(driverName, "file:"+dbPath+"?_txlock=immediate")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := raw.Exec(`
		CREATE TABLE schema_migrations (version TEXT PRIMARY KEY, applied_at INTEGER NOT NULL);
		CREATE TABLE users (
			id TEXT PRIMARY KEY,
			is_suspended INTEGER NOT NULL DEFAULT 0,
			global_stats_opt_in INTEGER NOT NULL DEFAULT 0
		);
		CREATE TABLE listening_sessions (id TEXT PRIMARY KEY, started_at INTEGER NOT NULL);
		INSERT INTO schema_migrations(version, applied_at) VALUES
			('000001_initial_schema.up.sql',0),
			('000002_device_token_expiry.up.sql',0),
			('000003_episode_transcripts.up.sql',0),
			('000004_sync_log_compaction_index.up.sql',0),
			('000005_listening_sessions.up.sql',0),
			('000007_episode_chapters.up.sql',0),
			('000011_sync_hardening.up.sql',0)
	`); err != nil {
		t.Fatal(err)
	}
	if err := raw.Close(); err != nil {
		t.Fatal(err)
	}

	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed to repair partial migration: %v", err)
	}
	defer database.Close()

	var applied int
	if err := database.SQL.QueryRow(`
		SELECT COUNT(*) FROM schema_migrations WHERE version='000006_global_statistics_opt_in.up.sql'
	`).Scan(&applied); err != nil || applied != 1 {
		t.Fatalf("migration not recorded: applied=%d err=%v", applied, err)
	}
	var name string
	if err := database.SQL.QueryRow(`
		SELECT name FROM pragma_table_info('users') WHERE name='global_stats_opt_in_at'
	`).Scan(&name); err != nil {
		t.Fatalf("missing repaired column: %v", err)
	}
	if err := database.SQL.QueryRow(`
		SELECT name FROM sqlite_master WHERE type='index' AND name='idx_users_global_stats_opt_in'
	`).Scan(&name); err != nil {
		t.Fatalf("missing repaired index: %v", err)
	}
}

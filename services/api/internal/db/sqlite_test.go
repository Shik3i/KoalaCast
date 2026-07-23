package db

import (
	"io/ioutil"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
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
	tables := []string{"users", "sessions", "podcasts", "episodes", "subscriptions", "playback_states", "sync_log"}
	for _, table := range tables {
		var name string
		err := db.SQL.QueryRow("SELECT name FROM sqlite_master WHERE type='table' AND name=?", table).Scan(&name)
		if err != nil {
			t.Errorf("expected table %s to exist, error: %v", table, err)
		}
	}
}

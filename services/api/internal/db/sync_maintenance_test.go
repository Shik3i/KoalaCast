package db

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
)

func TestCompactSyncLog(t *testing.T) {
	dir := t.TempDir()
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := OpenDB(filepath.Join(dir, "test.db"), logger)
	if err != nil {
		t.Fatalf("OpenDB: %v", err)
	}
	defer database.Close()

	if _, err := database.SQL.Exec(`
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, role, is_suspended, created_at, updated_at)
		VALUES ('u1','User','user','x','y','user',0,0,0)
	`); err != nil {
		t.Fatalf("seed user: %v", err)
	}

	ins := func(opID, entityType, entityID string, cursor int64) {
		_, err := database.SQL.Exec(`
			INSERT INTO sync_log (user_id, device_id, client_op_id, entity_type, entity_id, action, payload_json, client_timestamp, server_timestamp, server_cursor)
			VALUES ('u1','devA',?,?,?,'upsert','{}',0,0,?)
		`, opID, entityType, entityID, cursor)
		if err != nil {
			t.Fatalf("insert sync_log: %v", err)
		}
	}

	// Three progress ticks for the same episode, plus one subscription.
	ins("p:ep1:1", "playback_state", "ep1", 1)
	ins("p:ep1:2", "playback_state", "ep1", 2)
	ins("p:ep1:3", "playback_state", "ep1", 3)
	ins("s:pod1:9", "subscription", "pod1", 4)

	deleted, err := database.CompactSyncLog(context.Background())
	if err != nil {
		t.Fatalf("CompactSyncLog: %v", err)
	}
	if deleted != 2 {
		t.Fatalf("expected 2 superseded rows deleted, got %d", deleted)
	}

	// ep1 should retain only its newest op (cursor 3); pod1 untouched.
	var epCursor int64
	if err := database.SQL.QueryRow(
		"SELECT server_cursor FROM sync_log WHERE entity_id = 'ep1'").Scan(&epCursor); err != nil {
		t.Fatalf("query ep1: %v", err)
	}
	if epCursor != 3 {
		t.Errorf("expected ep1 to retain cursor 3, got %d", epCursor)
	}

	var total int
	if err := database.SQL.QueryRow("SELECT COUNT(*) FROM sync_log").Scan(&total); err != nil {
		t.Fatalf("count: %v", err)
	}
	if total != 2 {
		t.Errorf("expected 2 rows retained (ep1 latest + pod1), got %d", total)
	}
}

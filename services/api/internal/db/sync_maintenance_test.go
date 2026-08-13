package db

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
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

	ins := func(opID, deviceID, entityType, entityID string, clientTimestamp, cursor int64) {
		_, err := database.SQL.Exec(`
			INSERT INTO sync_log (user_id, device_id, client_op_id, entity_type, entity_id, action, payload_json, client_timestamp, server_timestamp, server_cursor)
			VALUES ('u1',?,?,?,?,'upsert','{}',?,0,?)
		`, deviceID, opID, entityType, entityID, clientTimestamp, cursor)
		if err != nil {
			t.Fatalf("insert sync_log: %v", err)
		}
	}

	// Playback keeps the latest accepted cursor because per-session sequence wins
	// over wall-clock time. Other entities use timestamp/device conflict ordering.
	ins("p:ep1:1", "devA", "playback_state", "ep1", 100, 1)
	ins("p:ep1:2", "devA", "playback_state", "ep1", 200, 2)
	ins("p:ep1:3", "devA", "playback_state", "ep1", 150, 3)
	ins("s:pod1:9", "devA", "subscription", "pod1", 400, 4)
	ins("s:pod1:stale", "devZ", "subscription", "pod1", 150, 5)
	if _, err := database.SQL.Exec(`
		INSERT INTO processed_sync_operations (user_id, device_id, client_op_id, server_cursor, processed_at)
		VALUES ('u1','devA','expired',4,?), ('u1','devA','current',4,?)
	`, time.Now().Add(-syncOperationLedgerRetention-time.Hour).UnixMilli(), time.Now().UnixMilli()); err != nil {
		t.Fatalf("seed operation ledger: %v", err)
	}

	deleted, err := database.CompactSyncLog(context.Background())
	if err != nil {
		t.Fatalf("CompactSyncLog: %v", err)
	}
	if deleted != 3 {
		t.Fatalf("expected 3 superseded rows deleted, got %d", deleted)
	}

	// ep1 retains its latest accepted sequence (cursor 3), while pod1 rejects the
	// later-appended but older timestamp (cursor 5).
	var epCursor int64
	if err := database.SQL.QueryRow(
		"SELECT server_cursor FROM sync_log WHERE entity_id = 'ep1'").Scan(&epCursor); err != nil {
		t.Fatalf("query ep1: %v", err)
	}
	if epCursor != 3 {
		t.Errorf("expected ep1 to retain cursor 3, got %d", epCursor)
	}
	var subscriptionCursor int64
	if err := database.SQL.QueryRow(
		"SELECT server_cursor FROM sync_log WHERE entity_id = 'pod1'").Scan(&subscriptionCursor); err != nil {
		t.Fatalf("query pod1: %v", err)
	}
	if subscriptionCursor != 4 {
		t.Errorf("expected pod1 to retain conflict winner cursor 4, got %d", subscriptionCursor)
	}

	var total int
	if err := database.SQL.QueryRow("SELECT COUNT(*) FROM sync_log").Scan(&total); err != nil {
		t.Fatalf("count: %v", err)
	}
	if total != 2 {
		t.Errorf("expected 2 rows retained (ep1 latest + pod1), got %d", total)
	}
	var ledgerOps string
	if err := database.SQL.QueryRow("SELECT client_op_id FROM processed_sync_operations").Scan(&ledgerOps); err != nil {
		t.Fatalf("query retained operation: %v", err)
	}
	if ledgerOps != "current" {
		t.Errorf("expected only current operation retained, got %q", ledgerOps)
	}
	var minRetained int64
	if err := database.SQL.QueryRow(`SELECT min_retained_cursor FROM user_sync_cursors WHERE user_id = 'u1'`).Scan(&minRetained); err != nil {
		t.Fatalf("query min retained cursor: %v", err)
	}
	if minRetained != 3 {
		t.Errorf("expected oldest retained cursor 3, got %d", minRetained)
	}
}

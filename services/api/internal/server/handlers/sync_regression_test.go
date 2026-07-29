package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
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

func newSyncTestHandler(t *testing.T) (*SyncHandler, *db.DB, context.Context) {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "sync.db"), logger)
	if err != nil {
		t.Fatalf("OpenDB: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })
	if _, err := database.SQL.Exec(`
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at)
		VALUES ('u1','User','user','hash','recovery',0,0);
		INSERT INTO podcasts (id, feed_url, title, created_at, updated_at)
		VALUES ('pod-1','https://example.com/feed.xml','Podcast',0,0);
		UPDATE podcasts SET artwork_url='https://example.com/podcast.jpg' WHERE id='pod-1';
		INSERT INTO episodes (id, podcast_id, stable_identity_key, title, enclosure_url, duration_ms, artwork_url, created_at)
		VALUES ('ep-1','pod-1','ep-1','Episode','https://example.com/ep.mp3',60000,'https://example.com/episode.jpg',0)
	`); err != nil {
		t.Fatalf("seed: %v", err)
	}
	authCtx := context.WithValue(context.Background(), customMiddleware.UserContextKey, &customMiddleware.AuthUser{ID: "u1"})
	return &SyncHandler{DB: database}, database, authCtx
}

func pushSync(t *testing.T, handler *SyncHandler, ctx context.Context, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/sync", bytes.NewBufferString(body)).WithContext(ctx)
	rec := httptest.NewRecorder()
	handler.Push(rec, req)
	return rec
}

func TestSyncPullPaginatesWithoutSkippingCursor(t *testing.T) {
	handler, database, authCtx := newSyncTestHandler(t)
	tx, err := database.SQL.Begin()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tx.Exec(`INSERT INTO user_sync_cursors (user_id, current_cursor, min_retained_cursor, protocol_version, client_schema_version) VALUES ('u1',501,0,1,1)`); err != nil {
		t.Fatal(err)
	}
	for cursor := 1; cursor <= 501; cursor++ {
		if _, err := tx.Exec(`
			INSERT INTO sync_log (user_id, device_id, client_op_id, entity_type, entity_id, action, payload_json, client_timestamp, server_timestamp, server_cursor)
			VALUES ('u1','dev',?,'subscription',?,'upsert','{}',0,0,?)
		`, fmt.Sprintf("op-%d", cursor), fmt.Sprintf("pod-%d", cursor), cursor); err != nil {
			t.Fatal(err)
		}
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}

	pull := func(cursor int64) struct {
		NextCursor int64          `json:"next_cursor"`
		Head       int64          `json:"current_cursor"`
		HasMore    bool           `json:"has_more"`
		Changes    []SyncLogEntry `json:"changesets"`
	} {
		req := httptest.NewRequest(http.MethodGet, fmt.Sprintf("/api/v1/sync?since_cursor=%d&limit=500", cursor), nil).WithContext(authCtx)
		rec := httptest.NewRecorder()
		handler.Pull(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("pull status %d: %s", rec.Code, rec.Body.String())
		}
		var result struct {
			NextCursor int64          `json:"next_cursor"`
			Head       int64          `json:"current_cursor"`
			HasMore    bool           `json:"has_more"`
			Changes    []SyncLogEntry `json:"changesets"`
		}
		if err := json.NewDecoder(rec.Body).Decode(&result); err != nil {
			t.Fatal(err)
		}
		return result
	}

	first := pull(0)
	if len(first.Changes) != 500 || first.NextCursor != 500 || first.Head != 501 || !first.HasMore {
		t.Fatalf("bad first page: count=%d next=%d head=%d more=%v", len(first.Changes), first.NextCursor, first.Head, first.HasMore)
	}
	second := pull(first.NextCursor)
	if len(second.Changes) != 1 || second.NextCursor != 501 || second.HasMore {
		t.Fatalf("bad second page: count=%d next=%d more=%v", len(second.Changes), second.NextCursor, second.HasMore)
	}
}

func TestSyncPushRollsBackWholeBatchOnMaterializationError(t *testing.T) {
	handler, database, authCtx := newSyncTestHandler(t)
	body := `{"operations":[
		{"client_op_id":"valid","device_id":"dev","entity_type":"subscription","action":"upsert","entity_id":"pod-1","payload":{}},
		{"client_op_id":"invalid","device_id":"dev","entity_type":"favorite","action":"upsert","entity_id":"missing-episode","payload":{}}
	]}`
	rec := pushSync(t, handler, authCtx, body)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
	for _, query := range []string{
		`SELECT COUNT(*) FROM subscriptions`,
		`SELECT COUNT(*) FROM sync_log`,
		`SELECT COUNT(*) FROM processed_sync_operations`,
	} {
		var count int
		if err := database.SQL.QueryRow(query).Scan(&count); err != nil || count != 0 {
			t.Fatalf("%s: count=%d err=%v", query, count, err)
		}
	}
}

func TestSyncLedgerSurvivesCompactionAndPreventsReplay(t *testing.T) {
	handler, database, authCtx := newSyncTestHandler(t)
	upsert := `{"operations":[{"client_op_id":"sub-upsert","device_id":"dev","entity_type":"subscription","action":"upsert","entity_id":"pod-1","payload":{}}]}`
	deleteOp := `{"operations":[{"client_op_id":"sub-delete","device_id":"dev","entity_type":"subscription","action":"delete","entity_id":"pod-1","payload":{}}]}`
	if rec := pushSync(t, handler, authCtx, upsert); rec.Code != http.StatusOK {
		t.Fatal(rec.Body.String())
	}
	if rec := pushSync(t, handler, authCtx, deleteOp); rec.Code != http.StatusOK {
		t.Fatal(rec.Body.String())
	}
	if _, err := database.CompactSyncLog(context.Background()); err != nil {
		t.Fatal(err)
	}
	rec := pushSync(t, handler, authCtx, upsert)
	if rec.Code != http.StatusOK {
		t.Fatal(rec.Body.String())
	}
	var deleted int
	if err := database.SQL.QueryRow(`SELECT is_deleted FROM subscriptions WHERE user_id='u1' AND podcast_id='pod-1'`).Scan(&deleted); err != nil {
		t.Fatal(err)
	}
	if deleted != 1 {
		t.Fatal("compacted operation replay resurrected deleted subscription")
	}
}

func TestSyncSnapshotReturnsMaterializedRecordsAtCursor(t *testing.T) {
	handler, _, authCtx := newSyncTestHandler(t)
	now := time.Now().UnixMilli()
	body := fmt.Sprintf(`{"operations":[
		{"client_op_id":"sub","device_id":"dev","entity_type":"subscription","action":"upsert","entity_id":"pod-1","payload":{}},
		{"client_op_id":"fav","device_id":"dev","entity_type":"favorite","action":"upsert","entity_id":"ep-1","payload":{}},
		{"client_op_id":"play","device_id":"dev","entity_type":"playback_state","action":"upsert","entity_id":"ep-1","payload":{"episode_id":"ep-1","position_ms":12,"progress_percent":1,"event_type":"PROGRESS_TICK","per_session_seq":1,"client_timestamp":%d}},
		{"client_op_id":"listen","device_id":"dev","entity_type":"listening_session","action":"upsert","entity_id":"listen-1","payload":{"id":"listen-1","episode_id":"ep-1","podcast_id":"pod-1","categories":["Technology"],"started_at":%d,"ended_at":%d,"wall_clock_ms":1000}}
	]}`, now, now, now+1000)
	if rec := pushSync(t, handler, authCtx, body); rec.Code != http.StatusOK {
		t.Fatalf("push: %d %s", rec.Code, rec.Body.String())
	}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/sync/snapshot", nil).WithContext(authCtx)
	rec := httptest.NewRecorder()
	handler.Snapshot(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("snapshot: %d %s", rec.Code, rec.Body.String())
	}
	var snapshot struct {
		Cursor            int64            `json:"cursor"`
		Subscriptions     []map[string]any `json:"subscriptions"`
		Favorites         []map[string]any `json:"favorites"`
		PlaybackStates    []map[string]any `json:"playback_states"`
		ListeningSessions []map[string]any `json:"listening_sessions"`
		PodcastSettings   []map[string]any `json:"podcast_settings"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.Cursor != 4 || len(snapshot.Subscriptions) != 1 || len(snapshot.Favorites) != 1 ||
		len(snapshot.PlaybackStates) != 1 || len(snapshot.ListeningSessions) != 1 ||
		snapshot.PodcastSettings == nil {
		t.Fatalf("incomplete snapshot: %+v", snapshot)
	}
	if snapshot.Subscriptions[0]["feed_url"] != "https://example.com/feed.xml" ||
		snapshot.Subscriptions[0]["title"] != "Podcast" {
		t.Fatalf("subscription metadata missing: %+v", snapshot.Subscriptions[0])
	}
	for _, record := range []map[string]any{snapshot.Favorites[0], snapshot.PlaybackStates[0]} {
		if record["podcast_id"] != "pod-1" || record["title"] != "Episode" ||
			record["podcast_title"] != "Podcast" ||
			record["enclosure_url"] != "https://example.com/ep.mp3" ||
			record["duration_ms"] != float64(60000) {
			t.Fatalf("episode metadata missing: %+v", record)
		}
		categories, ok := record["categories"].([]any)
		if !ok || len(categories) != 1 || categories[0] != "Technology" {
			t.Fatalf("categories missing: %+v", record)
		}
	}
	if snapshot.PlaybackStates[0]["last_played_at"] == float64(0) {
		t.Fatalf("playback last_played_at missing: %+v", snapshot.PlaybackStates[0])
	}
}

func TestRejectedPassivePlaybackTickIsIdempotentButNotDistributed(t *testing.T) {
	handler, database, authCtx := newSyncTestHandler(t)
	now := time.Now().UnixMilli()
	completed := fmt.Sprintf(`{"operations":[{
		"client_op_id":"completed","device_id":"dev","entity_type":"playback_state",
		"action":"upsert","entity_id":"ep-1",
		"payload":{"episode_id":"ep-1","position_ms":60000,"completed":true,
		"progress_percent":100,"event_type":"MARK_PLAYED","playback_session_id":"session",
		"per_session_seq":2,"client_timestamp":%d}
	}]}`, now)
	stale := fmt.Sprintf(`{"operations":[{
		"client_op_id":"stale","device_id":"dev","entity_type":"playback_state",
		"action":"upsert","entity_id":"ep-1",
		"payload":{"episode_id":"ep-1","position_ms":1000,"completed":false,
		"progress_percent":1,"event_type":"PROGRESS_TICK","playback_session_id":"session",
		"per_session_seq":1,"client_timestamp":%d}
	}]}`, now-1000)
	for _, body := range []string{completed, stale, stale} {
		if rec := pushSync(t, handler, authCtx, body); rec.Code != http.StatusOK {
			t.Fatalf("push: %d %s", rec.Code, rec.Body.String())
		}
	}

	var logCount, ledgerCount, completedValue int
	if err := database.SQL.QueryRow(`SELECT COUNT(*) FROM sync_log`).Scan(&logCount); err != nil {
		t.Fatal(err)
	}
	if err := database.SQL.QueryRow(`SELECT COUNT(*) FROM processed_sync_operations`).Scan(&ledgerCount); err != nil {
		t.Fatal(err)
	}
	if err := database.SQL.QueryRow(`SELECT completed FROM playback_states WHERE user_id='u1' AND episode_id='ep-1'`).Scan(&completedValue); err != nil {
		t.Fatal(err)
	}
	if logCount != 1 || ledgerCount != 2 || completedValue != 1 {
		t.Fatalf("rejected tick leaked: log=%d ledger=%d completed=%d", logCount, ledgerCount, completedValue)
	}
}

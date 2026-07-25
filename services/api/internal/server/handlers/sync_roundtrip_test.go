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

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
)

// TestSync_PayloadRoundTrip verifies that a pushed operation's full payload is
// returned verbatim by Pull. The web client relies on this: it pushes the entire
// local record (feed_url/title/artwork), so another device reconstructs it from
// the pulled changeset without any per-item hydration fetch.
func TestSync_PayloadRoundTrip(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "koala_sync_rt_*")
	if err != nil {
		t.Fatalf("temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(tempDir, "test.db"), logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	// A user row is required for the sync_log / cursor foreign keys.
	if _, err := database.SQL.Exec(`
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, role, is_suspended, created_at, updated_at)
		VALUES ('u1','User','user','x','y','user',0,0,0)
	`); err != nil {
		t.Fatalf("seed user: %v", err)
	}

	h := &SyncHandler{DB: database}
	authCtx := context.WithValue(context.Background(), customMiddleware.UserContextKey, &customMiddleware.AuthUser{ID: "u1", Role: "user"})

	// Push a subscription op whose payload carries denormalized metadata.
	pushBody := `{
		"client_schema_version": 1,
		"operations": [{
			"client_op_id": "s:pod-1:100",
			"device_id": "dev-A",
			"entity_type": "subscription",
			"action": "upsert",
			"entity_id": "pod-1",
			"payload": {"podcast_id":"pod-1","feed_url":"https://example.com/feed.xml","title":"My Show","artwork_url":"https://cdn/x.jpg","added_at":100},
			"client_timestamp": 100
		}]
	}`
	reqPush := httptest.NewRequest(http.MethodPost, "/api/v1/sync", bytes.NewBufferString(pushBody)).WithContext(authCtx)
	recPush := httptest.NewRecorder()
	h.Push(recPush, reqPush)
	if recPush.Code != http.StatusOK {
		t.Fatalf("push: expected 200, got %d: %s", recPush.Code, recPush.Body.String())
	}

	// Pull from cursor 0 and confirm the payload survived intact.
	reqPull := httptest.NewRequest(http.MethodGet, "/api/v1/sync?since_cursor=0", nil).WithContext(authCtx)
	recPull := httptest.NewRecorder()
	h.Pull(recPull, reqPull)
	if recPull.Code != http.StatusOK {
		t.Fatalf("pull: expected 200, got %d", recPull.Code)
	}

	var pull struct {
		Changesets []struct {
			EntityType string          `json:"entity_type"`
			EntityID   string          `json:"entity_id"`
			Action     string          `json:"action"`
			Payload    json.RawMessage `json:"payload"`
		} `json:"changesets"`
	}
	if err := json.NewDecoder(recPull.Body).Decode(&pull); err != nil {
		t.Fatalf("decode pull: %v", err)
	}
	if len(pull.Changesets) != 1 {
		t.Fatalf("expected 1 changeset, got %d", len(pull.Changesets))
	}

	cs := pull.Changesets[0]
	if cs.EntityType != "subscription" || cs.EntityID != "pod-1" || cs.Action != "upsert" {
		t.Fatalf("unexpected changeset envelope: %+v", cs)
	}

	var payload struct {
		FeedURL    string `json:"feed_url"`
		Title      string `json:"title"`
		ArtworkURL string `json:"artwork_url"`
	}
	if err := json.Unmarshal(cs.Payload, &payload); err != nil {
		t.Fatalf("payload not valid JSON: %v (raw=%s)", err, cs.Payload)
	}
	if payload.FeedURL != "https://example.com/feed.xml" || payload.Title != "My Show" || payload.ArtworkURL != "https://cdn/x.jpg" {
		t.Fatalf("payload did not round-trip verbatim: %+v", payload)
	}
}

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

// TestSync_PlaybackPayloadKeepsDenormalizedMetadata pins the contract both
// clients depend on: a playback_state payload carries the episode's title,
// artwork, podcast and duration so the receiving device can render "continue
// listening" without a second fetch. Normalizing the operation must not throw
// those keys away — the sync log is the only place they exist.
func TestSync_PlaybackPayloadKeepsDenormalizedMetadata(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "koala_sync_pb_*")
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

	if _, err := database.SQL.Exec(`
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, role, is_suspended, created_at, updated_at)
		VALUES ('u1','User','user','x','y','user',0,0,0);
		INSERT INTO podcasts (id, feed_url, title, created_at, updated_at)
		VALUES ('pod-1','https://example.com/feed.xml','My Show',0,0);
		INSERT INTO episodes (id, podcast_id, stable_identity_key, title, enclosure_url, created_at)
		VALUES ('ep-1','pod-1','guid-1','Episode One','https://cdn/x.mp3',0)
	`); err != nil {
		t.Fatalf("seed: %v", err)
	}

	h := &SyncHandler{DB: database}
	authCtx := context.WithValue(context.Background(), customMiddleware.UserContextKey, &customMiddleware.AuthUser{ID: "u1", Role: "user"})

	pushBody := `{
		"client_schema_version": 2,
		"operations": [{
			"client_op_id": "p:ep-1:100",
			"device_id": "dev-A",
			"entity_type": "playback_state",
			"action": "upsert",
			"entity_id": "ep-1",
			"payload": {
				"episode_id":"ep-1",
				"podcast_id":"pod-1",
				"position_ms":42000,
				"completed":false,
				"progress_percent":12.5,
				"last_played_at":100,
				"title":"Episode One",
				"podcast_title":"My Show",
				"artwork_url":"https://cdn/x.jpg",
				"enclosure_url":"https://cdn/x.mp3",
				"duration_ms":360000,
				"categories":["News"],
				"event_type":"PROGRESS_TICK",
				"playback_session_id":"sess-1",
				"device_id":"dev-A",
				"per_session_seq":3,
				"client_timestamp":100
			},
			"client_timestamp": 100
		}]
	}`
	reqPush := httptest.NewRequest(http.MethodPost, "/api/v1/sync", bytes.NewBufferString(pushBody)).WithContext(authCtx)
	recPush := httptest.NewRecorder()
	h.Push(recPush, reqPush)
	if recPush.Code != http.StatusOK {
		t.Fatalf("push: expected 200, got %d: %s", recPush.Code, recPush.Body.String())
	}

	reqPull := httptest.NewRequest(http.MethodGet, "/api/v1/sync?since_cursor=0", nil).WithContext(authCtx)
	recPull := httptest.NewRecorder()
	h.Pull(recPull, reqPull)
	if recPull.Code != http.StatusOK {
		t.Fatalf("pull: expected 200, got %d", recPull.Code)
	}

	var pull struct {
		Changesets []struct {
			EntityType string          `json:"entity_type"`
			Payload    json.RawMessage `json:"payload"`
		} `json:"changesets"`
	}
	if err := json.NewDecoder(recPull.Body).Decode(&pull); err != nil {
		t.Fatalf("decode pull: %v", err)
	}
	if len(pull.Changesets) != 1 || pull.Changesets[0].EntityType != "playback_state" {
		t.Fatalf("expected one playback changeset, got %+v", pull.Changesets)
	}

	var payload struct {
		PodcastID     string   `json:"podcast_id"`
		Title         string   `json:"title"`
		PodcastTitle  string   `json:"podcast_title"`
		ArtworkURL    string   `json:"artwork_url"`
		EnclosureURL  string   `json:"enclosure_url"`
		DurationMS    int64    `json:"duration_ms"`
		LastPlayedAt  int64    `json:"last_played_at"`
		Categories    []string `json:"categories"`
		PositionMS    int64    `json:"position_ms"`
		EventType     string   `json:"event_type"`
		PerSessionSeq int64    `json:"per_session_seq"`
	}
	if err := json.Unmarshal(pull.Changesets[0].Payload, &payload); err != nil {
		t.Fatalf("payload not valid JSON: %v (raw=%s)", err, pull.Changesets[0].Payload)
	}
	if payload.PodcastID != "pod-1" || payload.Title != "Episode One" ||
		payload.PodcastTitle != "My Show" || payload.ArtworkURL != "https://cdn/x.jpg" ||
		payload.EnclosureURL != "https://cdn/x.mp3" || payload.DurationMS != 360000 ||
		payload.LastPlayedAt != 100 || len(payload.Categories) != 1 {
		t.Fatalf("denormalized metadata was dropped: %+v", payload)
	}
	if payload.PositionMS != 42000 || payload.EventType != "PROGRESS_TICK" || payload.PerSessionSeq != 3 {
		t.Fatalf("normalized fields did not survive: %+v", payload)
	}
}

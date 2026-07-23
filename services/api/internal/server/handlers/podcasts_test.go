package handlers

import (
	"bytes"
	"encoding/json"
	"io/ioutil"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/podcastindex"
)

func TestPodcastHandler_SearchUnconfigured(t *testing.T) {
	idxClient := podcastindex.NewClient("", "")
	handler := &PodcastHandler{
		PodcastIndex: idxClient,
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/podcasts/search?q=test", nil)
	rec := httptest.NewRecorder()

	handler.Search(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var resp map[string]interface{}
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if resp["search_available"] != false {
		t.Errorf("expected search_available to be false when unconfigured")
	}
}

func TestPodcastHandler_AddFeed_SSRFValidation(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_pod_test_*")
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

	handler := &PodcastHandler{
		DB:           database,
		MaxResponseB: 10485760,
	}

	// Try adding a loopback feed URL -> Must be blocked by SSRF filter
	reqBody := []byte(`{"feed_url":"http://127.0.0.1/private_rss.xml"}`)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/podcasts/feed", bytes.NewBuffer(reqBody))
	rec := httptest.NewRecorder()

	handler.AddFeed(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("expected status 400 for loopback RSS feed URL, got %d", rec.Code)
	}
}

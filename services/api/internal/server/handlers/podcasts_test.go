package handlers

import (
	"bytes"
	"encoding/json"
	"io"
	"io/ioutil"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/itunes"
	"github.com/Shik3i/KoalaCast/services/api/internal/podcastindex"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (fn roundTripperFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return fn(req)
}

func TestPodcastHandler_SearchUnconfigured(t *testing.T) {
	idxClient := podcastindex.NewClient("", "")
	httpClient := &http.Client{
		Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
			if req.URL.Host != "itunes.apple.com" || req.URL.Query().Get("term") != "test" {
				t.Fatalf("unexpected iTunes request: %s", req.URL.String())
			}
			body := `{"resultCount":1,"results":[{"trackId":42,"trackName":"Test Cast","artistName":"Koala","feedUrl":"https://example.com/feed.xml"}]}`
			return &http.Response{
				StatusCode: http.StatusOK,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader(body)),
			}, nil
		}),
	}
	handler := &PodcastHandler{
		PodcastIndex: idxClient,
		ITunes:       itunes.NewITunesClientWithHTTPClient(httpClient),
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

	if resp["search_available"] != true {
		t.Errorf("expected search_available to be true via iTunes search fallback")
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

func TestPodcastHandler_Search_WithPodcastIndexCategories(t *testing.T) {
	mockResp := `{
		"status": "true",
		"feeds": [
			{
				"id": 42,
				"title": "Tech Talk",
				"url": "https://example.com/feed.xml",
				"author": "Techie",
				"artwork": "https://example.com/art.jpg",
				"description": "A tech podcast",
				"categories": {"102": "Technology", "105": "Science"}
			}
		],
		"count": 1
	}`

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(mockResp))
	}))
	defer ts.Close()

	idxClient := podcastindex.NewClient("key", "secret")
	idxClient.SetBaseURL(ts.URL)
	idxClient.SetHTTPClient(rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true}))
	handler := &PodcastHandler{
		PodcastIndex: idxClient,
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/podcasts/search?q=Tech", nil)
	rec := httptest.NewRecorder()

	handler.Search(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var resp struct {
		Results []itunes.PodcastResult `json:"results"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode search response: %v", err)
	}

	if len(resp.Results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(resp.Results))
	}
	if len(resp.Results[0].Categories) == 0 {
		t.Errorf("expected categories in DTO result, got none")
	}
}

func TestPodcastHandler_Discover_WithPodcastIndexCategories(t *testing.T) {
	mockResp := `{
		"status": "true",
		"feeds": [
			{
				"id": 99,
				"title": "Science Daily",
				"url": "https://example.com/science.xml",
				"author": "Researcher",
				"artwork": "https://example.com/science.jpg",
				"description": "Daily science news",
				"categories": {"105": "Science"}
			}
		],
		"count": 1
	}`

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(mockResp))
	}))
	defer ts.Close()

	idxClient := podcastindex.NewClient("key", "secret")
	idxClient.SetBaseURL(ts.URL)
	idxClient.SetHTTPClient(rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true}))
	handler := &PodcastHandler{
		PodcastIndex: idxClient,
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/podcasts/discover?category=Science", nil)
	rec := httptest.NewRecorder()

	handler.Discover(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var resp struct {
		Results []itunes.PodcastResult `json:"results"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode discover response: %v", err)
	}

	if len(resp.Results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(resp.Results))
	}
	if len(resp.Results[0].Categories) == 0 || resp.Results[0].Categories[0] != "Science" {
		t.Errorf("expected category Science in discover result, got %v", resp.Results[0].Categories)
	}
}

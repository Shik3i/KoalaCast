package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"io/ioutil"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/itunes"
	"github.com/Shik3i/KoalaCast/services/api/internal/podcastindex"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	"github.com/go-chi/chi/v5"
)

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (fn roundTripperFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return fn(req)
}

func TestPodcastHandler_GetEpisodesIncrementalAndConditional(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "episodes-cache.db"), logger)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	if _, err := database.SQL.Exec(`
		INSERT INTO podcasts (id, feed_url, title, created_at, updated_at)
		VALUES ('p1', 'https://example.com/feed.xml', 'Cached show', 1, 1)
	`); err != nil {
		t.Fatal(err)
	}
	for _, episode := range []struct {
		id      string
		pubDate int64
	}{
		{"e1", 100},
		{"e2", 200},
		{"e3", 300},
	} {
		if _, err := database.SQL.Exec(`
			INSERT INTO episodes
				(id, podcast_id, stable_identity_key, title, pub_date, has_pub_date, enclosure_url, created_at)
			VALUES (?, 'p1', ?, ?, ?, 1, ?, ?)
		`, episode.id, episode.id, episode.id, episode.pubDate, "https://cdn.example/"+episode.id+".mp3", episode.pubDate); err != nil {
			t.Fatal(err)
		}
	}

	handler := &PodcastHandler{DB: database}
	request := func(target, etag string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, target, nil)
		if etag != "" {
			req.Header.Set("If-None-Match", etag)
		}
		route := chi.NewRouteContext()
		route.URLParams.Add("id", "p1")
		req = req.WithContext(context.WithValue(req.Context(), chi.RouteCtxKey, route))
		rec := httptest.NewRecorder()
		handler.GetEpisodes(rec, req)
		return rec
	}

	first := request("/api/v1/podcasts/p1/episodes?since=200", "")
	if first.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", first.Code, first.Body.String())
	}
	var body struct {
		Episodes []EpisodeResponse `json:"episodes"`
		Since    int64             `json:"since"`
	}
	if err := json.NewDecoder(first.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Since != 200 || len(body.Episodes) != 2 ||
		body.Episodes[0].ID != "e3" || body.Episodes[1].ID != "e2" {
		t.Fatalf("unexpected incremental response: %#v", body)
	}
	etag := first.Header().Get("ETag")
	if etag == "" {
		t.Fatal("missing ETag")
	}
	second := request("/api/v1/podcasts/p1/episodes?since=200", etag)
	if second.Code != http.StatusNotModified || second.Body.Len() != 0 {
		t.Fatalf("expected empty 304, got %d: %q", second.Code, second.Body.String())
	}
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

func TestPodcastHandler_SearchFallsBackWhenPodcastIndexRejectsCredentials(t *testing.T) {
	indexServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, `{"status":"false","description":"unauthorized"}`, http.StatusUnauthorized)
	}))
	defer indexServer.Close()

	idxClient := podcastindex.NewClient("expired-key", "expired-secret")
	idxClient.SetBaseURL(indexServer.URL)
	idxClient.SetHTTPClient(rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true}))

	itunesClient := &http.Client{
		Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
			if req.URL.Host != "itunes.apple.com" || req.URL.Query().Get("term") != "fallback" {
				t.Fatalf("unexpected iTunes request: %s", req.URL.String())
			}
			body := `{"resultCount":1,"results":[{"trackId":42,"trackName":"Fallback Cast","artistName":"Koala","feedUrl":"https://example.com/feed.xml"}]}`
			return &http.Response{
				StatusCode: http.StatusOK,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader(body)),
			}, nil
		}),
	}
	handler := &PodcastHandler{
		PodcastIndex: idxClient,
		ITunes:       itunes.NewITunesClientWithHTTPClient(itunesClient),
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/podcasts/search?q=fallback&region=de", nil)
	rec := httptest.NewRecorder()
	handler.Search(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected fallback status 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Provider string                 `json:"provider"`
		Results  []itunes.PodcastResult `json:"results"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode fallback response: %v", err)
	}
	if resp.Provider != "itunes" {
		t.Fatalf("expected iTunes fallback provider, got %q", resp.Provider)
	}
	if len(resp.Results) != 1 || resp.Results[0].Title != "Fallback Cast" {
		t.Fatalf("unexpected fallback results: %#v", resp.Results)
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

func TestPodcastHandler_ConcurrentFeedIngestIsIdempotent(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "concurrent-feed.db"), logger)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	var fetches atomic.Int32
	release := make(chan struct{})
	feedClient := &http.Client{Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
		fetches.Add(1)
		<-release
		body := `<?xml version="1.0"?><rss version="2.0"><channel><title>Concurrent</title><item><guid>ep-1</guid><title>Episode</title><enclosure url="https://cdn.example/ep.mp3" type="audio/mpeg"/></item></channel></rss>`
		return &http.Response{
			StatusCode: http.StatusOK,
			Header:     make(http.Header),
			Body:       io.NopCloser(strings.NewReader(body)),
			Request:    req,
		}, nil
	})}
	handler := &PodcastHandler{DB: database, MaxResponseB: 1024 * 1024, FeedHTTPClient: feedClient}

	const callers = 8
	ids := make(chan string, callers)
	errs := make(chan error, callers)
	var started sync.WaitGroup
	started.Add(callers)
	for range callers {
		go func() {
			started.Done()
			id, ingestErr := handler.IngestFeedURL(context.Background(), "https://feeds.example/concurrent.xml")
			ids <- id
			errs <- ingestErr
		}()
	}
	started.Wait()
	close(release)

	var firstID string
	for range callers {
		if ingestErr := <-errs; ingestErr != nil {
			t.Fatal(ingestErr)
		}
		id := <-ids
		if firstID == "" {
			firstID = id
		} else if id != firstID {
			t.Fatalf("concurrent ingest returned different IDs: %q != %q", id, firstID)
		}
	}
	if got := fetches.Load(); got != 1 {
		t.Fatalf("expected one feed fetch, got %d", got)
	}
}

func TestPodcastHandler_RedirectAliasesResolveToOnePodcast(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "aliases.db"), logger)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	const canonical = "https://feeds.example/canonical.xml"
	feedClient := &http.Client{Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
		switch req.URL.Path {
		case "/alias-a.xml", "/alias-b.xml":
			return &http.Response{
				StatusCode: http.StatusFound,
				Header:     http.Header{"Location": []string{canonical}},
				Body:       io.NopCloser(strings.NewReader("")),
				Request:    req,
			}, nil
		case "/canonical.xml":
			body := `<?xml version="1.0"?><rss version="2.0"><channel><title>Canonical</title><item><guid>ep-1</guid><title>Episode</title><enclosure url="https://cdn.example/ep.mp3" type="audio/mpeg"/></item></channel></rss>`
			return &http.Response{
				StatusCode: http.StatusOK,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader(body)),
				Request:    req,
			}, nil
		default:
			return &http.Response{StatusCode: http.StatusNotFound, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(""))}, nil
		}
	})}
	handler := &PodcastHandler{DB: database, MaxResponseB: 1024 * 1024, FeedHTTPClient: feedClient}

	firstID, err := handler.IngestFeedURL(context.Background(), "https://feeds.example/alias-a.xml")
	if err != nil {
		t.Fatal(err)
	}
	secondID, err := handler.IngestFeedURL(context.Background(), "https://feeds.example/alias-b.xml")
	if err != nil {
		t.Fatal(err)
	}
	if firstID != secondID {
		t.Fatalf("aliases created different podcasts: %s != %s", firstID, secondID)
	}
	var podcasts, aliases int
	if err := database.SQL.QueryRow(`SELECT COUNT(*) FROM podcasts`).Scan(&podcasts); err != nil {
		t.Fatal(err)
	}
	if err := database.SQL.QueryRow(`SELECT COUNT(*) FROM podcast_aliases WHERE target_podcast_id=?`, firstID).Scan(&aliases); err != nil {
		t.Fatal(err)
	}
	if podcasts != 1 || aliases != 3 {
		t.Fatalf("expected one podcast and three aliases, got podcasts=%d aliases=%d", podcasts, aliases)
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

func TestIncludeExplicitContentDefaultsToTrue(t *testing.T) {
	if !includeExplicitContent(httptest.NewRequest(http.MethodGet, "/discover", nil)) {
		t.Fatal("request without include_explicit must retain the legacy true default")
	}
	if includeExplicitContent(httptest.NewRequest(http.MethodGet, "/discover?include_explicit=false", nil)) {
		t.Fatal("include_explicit=false was ignored")
	}
}

func TestPodcastHandlerDiscoverFiltersOnlyExplicitAndFillsLimit(t *testing.T) {
	mockResp := `{
		"status":"true",
		"feeds":[
			{"id":1,"title":"Explicit","url":"https://example.com/1","explicit":1},
			{"id":2,"title":"Unknown","url":"https://example.com/2"},
			{"id":3,"title":"Clean","url":"https://example.com/3","explicit":0}
		]
	}`

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.URL.Query().Get("max"); got != "6" {
			t.Errorf("trending max = %q, want overfetched 6", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(mockResp))
	}))
	defer ts.Close()

	idxClient := podcastindex.NewClient("key", "secret")
	idxClient.SetBaseURL(ts.URL)
	idxClient.SetHTTPClient(rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true}))
	handler := &PodcastHandler{PodcastIndex: idxClient}
	rec := httptest.NewRecorder()
	handler.Discover(
		rec,
		httptest.NewRequest(
			http.MethodGet,
			"/api/v1/podcasts/discover?include_explicit=false&limit=2",
			nil,
		),
	)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Results []itunes.PodcastResult `json:"results"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if len(resp.Results) != 2 || resp.Results[0].Title != "Unknown" || resp.Results[1].Title != "Clean" {
		t.Fatalf("filtered results = %#v", resp.Results)
	}
	if resp.Results[0].Explicit != nil {
		t.Fatalf("missing explicit metadata became clean: %#v", resp.Results[0].Explicit)
	}
}

func TestPodcastHandlerDiscoverFiltersExplicitDatabaseFallback(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "explicit-fallback.db"), logger)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	for _, podcast := range []struct {
		id       string
		title    string
		explicit int
	}{
		{"explicit", "Explicit database show", 1},
		{"clean", "Clean database show", 0},
	} {
		if _, err := database.SQL.Exec(`
			INSERT INTO podcasts (id, feed_url, title, explicit, created_at, updated_at)
			VALUES (?, ?, ?, ?, 1, 1)
		`, podcast.id, "https://example.com/"+podcast.id+".xml", podcast.title, podcast.explicit); err != nil {
			t.Fatal(err)
		}
	}

	unavailableApple := itunes.NewITunesClientWithHTTPClient(&http.Client{
		Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
			return &http.Response{
				StatusCode: http.StatusServiceUnavailable,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader("unavailable")),
			}, nil
		}),
	})
	handler := &PodcastHandler{DB: database, ITunes: unavailableApple}
	rec := httptest.NewRecorder()
	handler.Discover(
		rec,
		httptest.NewRequest(
			http.MethodGet,
			"/api/v1/podcasts/discover?include_explicit=false&limit=10",
			nil,
		),
	)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Results []itunes.PodcastResult `json:"results"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if len(resp.Results) != 1 || resp.Results[0].ID != "clean" {
		t.Fatalf("database fallback leaked explicit content: %#v", resp.Results)
	}
}

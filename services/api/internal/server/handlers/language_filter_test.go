package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/itunes"
	"github.com/Shik3i/KoalaCast/services/api/internal/podcastindex"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

// mixedLanguageFeeds mimics a trending response from a German storefront: a
// German show, an English show, and one whose publisher omitted <language>.
const mixedLanguageFeeds = `{
	"status": "true",
	"feeds": [
		{
			"id": 1,
			"title": "Lage der Nation",
			"url": "https://example.com/lage.xml",
			"author": "Ulf und Philip",
			"artwork": "https://example.com/lage.jpg",
			"description": "Der Politik-Podcast aus Berlin",
			"language": "de",
			"categories": {"1": "News"}
		},
		{
			"id": 2,
			"title": "The Daily",
			"url": "https://example.com/daily.xml",
			"author": "The New York Times",
			"artwork": "https://example.com/daily.jpg",
			"description": "This is what the news should sound like",
			"language": "en-US",
			"categories": {"1": "News"}
		},
		{
			"id": 3,
			"title": "Untagged Show",
			"url": "https://example.com/untagged.xml",
			"author": "Someone",
			"artwork": "https://example.com/untagged.jpg",
			"description": "",
			"categories": {"1": "News"}
		}
	],
	"count": 3
}`

func newMixedLanguageHandler(t *testing.T) (*PodcastHandler, func()) {
	t.Helper()
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(mixedLanguageFeeds))
	}))

	idxClient := podcastindex.NewClient("key", "secret")
	idxClient.SetBaseURL(ts.URL)
	idxClient.SetHTTPClient(rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true}))
	return &PodcastHandler{PodcastIndex: idxClient}, ts.Close
}

func decodeResults(t *testing.T, rec *httptest.ResponseRecorder) []itunes.PodcastResult {
	t.Helper()
	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	var resp struct {
		Results []itunes.PodcastResult `json:"results"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	return resp.Results
}

func titles(results []itunes.PodcastResult) map[string]bool {
	out := make(map[string]bool, len(results))
	for _, r := range results {
		out[r.Title] = true
	}
	return out
}

// The whole point of the feature: asking for German must not return The Daily,
// even though it sits in the same (German storefront) chart.
func TestDiscover_FiltersEnglishOutOfGermanRequest(t *testing.T) {
	handler, cleanup := newMixedLanguageHandler(t)
	defer cleanup()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/podcasts/discover?region=de&languages=de", nil)
	rec := httptest.NewRecorder()
	handler.Discover(rec, req)

	got := titles(decodeResults(t, rec))
	if !got["Lage der Nation"] {
		t.Error("expected the German show to be kept")
	}
	if got["The Daily"] {
		t.Error("expected the English show to be filtered out of a German-only request")
	}
	if got["Untagged Show"] {
		t.Error("expected the language-less show to be excluded from an explicit language filter")
	}
}

func TestDiscover_NoLanguageParamReturnsEverything(t *testing.T) {
	handler, cleanup := newMixedLanguageHandler(t)
	defer cleanup()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/podcasts/discover?region=de", nil)
	rec := httptest.NewRecorder()
	handler.Discover(rec, req)

	if n := len(decodeResults(t, rec)); n != 3 {
		t.Errorf("expected all 3 results without a language filter, got %d", n)
	}
}

// A regional RSS tag ("en-US") must satisfy a bare "en" filter.
func TestDiscover_RegionalLanguageTagMatchesBareCode(t *testing.T) {
	handler, cleanup := newMixedLanguageHandler(t)
	defer cleanup()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/podcasts/discover?languages=en", nil)
	rec := httptest.NewRecorder()
	handler.Discover(rec, req)

	got := titles(decodeResults(t, rec))
	if !got["The Daily"] {
		t.Error("expected en-US to match a bare 'en' filter")
	}
	if got["Lage der Nation"] {
		t.Error("expected the German show to be filtered out of an English-only request")
	}
}

func TestSearch_FiltersByLanguageAndCategory(t *testing.T) {
	handler, cleanup := newMixedLanguageHandler(t)
	defer cleanup()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/podcasts/search?q=news&languages=de", nil)
	rec := httptest.NewRecorder()
	handler.Search(rec, req)

	got := titles(decodeResults(t, rec))
	if got["The Daily"] {
		t.Error("expected the English show to be filtered out of a German-only search")
	}
	if !got["Lage der Nation"] {
		t.Error("expected the German show to be kept")
	}

	// A category that nothing matches must come back empty rather than unfiltered.
	req = httptest.NewRequest(http.MethodGet, "/api/v1/podcasts/search?q=news&category=Comedy", nil)
	rec = httptest.NewRecorder()
	handler.Search(rec, req)
	if n := len(decodeResults(t, rec)); n != 0 {
		t.Errorf("expected 0 results for a non-matching category, got %d", n)
	}
}

func TestFilterByCategory_IsCaseInsensitiveAndNoOpForAll(t *testing.T) {
	results := []itunes.PodcastResult{
		{Title: "A", Category: "True Crime", Categories: []string{"True Crime"}},
		{Title: "B", Category: "News", Categories: []string{"News"}},
	}

	if got := filterByCategory(results, "true crime"); len(got) != 1 || got[0].Title != "A" {
		t.Errorf("expected case-insensitive match on 'true crime', got %v", got)
	}
	if got := filterByCategory(results, "all"); len(got) != 2 {
		t.Errorf("expected 'all' to be a no-op, got %d results", len(got))
	}
	if got := filterByCategory(results, ""); len(got) != 2 {
		t.Errorf("expected empty category to be a no-op, got %d results", len(got))
	}
}

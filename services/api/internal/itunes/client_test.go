package itunes

import (
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

func TestGenreIDForCategory(t *testing.T) {
	cases := map[string]int{
		"Technology":        1318,
		"technology":        1318,
		"  News  ":          1489,
		"Business":          1321,
		"Science":           1533,
		"Comedy":            1303,
		"Society":           1324,
		"Society & Culture": 1324,
		"Arts":              1301,
		"Education":         1304,
		"Health & Fitness":  1512,
		"True Crime":        1488,
		"TV & Film":         1309,
		"All":               0, // overall chart
		"":                  0,
		"Nonsense":          0,
	}
	for input, want := range cases {
		if got := GenreIDForCategory(input); got != want {
			t.Errorf("GenreIDForCategory(%q) = %d, want %d", input, got, want)
		}
	}
}

func TestSanitizeRegion(t *testing.T) {
	cases := map[string]string{
		"us":     "us",
		"DE":     "de",
		" gb ":   "gb",
		"":       "us", // empty → default
		"usa":    "us", // wrong length → default
		"u1":     "us", // non-alpha → default
		"../etc": "us", // path-injection attempt → default
	}
	for input, want := range cases {
		if got := sanitizeRegion(input); got != want {
			t.Errorf("sanitizeRegion(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestEnrichLatestEpisodesBatchesAndCachesLookup(t *testing.T) {
	var calls atomic.Int32
	client := NewITunesClientWithHTTPClient(&http.Client{
		Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			calls.Add(1)
			if req.URL.Query().Get("id") != "101,202" || req.URL.Query().Get("entity") != "podcastEpisode" {
				t.Fatalf("unexpected lookup URL: %s", req.URL.String())
			}
			body := `{"results":[
				{"wrapperType":"track","collectionId":101,"feedUrl":"https://example.com/one.xml"},
				{"wrapperType":"podcastEpisode","kind":"podcast-episode","collectionId":101,"trackTimeMillis":1234000,"releaseDate":"2026-07-28T08:00:00Z"},
				{"wrapperType":"track","collectionId":202,"feedUrl":"https://example.com/two.xml"},
				{"wrapperType":"podcastEpisode","kind":"podcast-episode","collectionId":202,"trackTimeMillis":5678000,"releaseDate":"2026-07-28T07:00:00Z"}
			]}`
			return &http.Response{
				StatusCode: http.StatusOK,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader(body)),
				Request:    req,
			}, nil
		}),
	})
	results := []PodcastResult{{ID: "101"}, {ID: "202"}}
	if err := client.EnrichLatestEpisodes(results); err != nil {
		t.Fatalf("EnrichLatestEpisodes failed: %v", err)
	}
	if results[0].LatestDurationMS != 1234000 || results[0].FeedURL != "https://example.com/one.xml" {
		t.Fatalf("unexpected first result: %+v", results[0])
	}
	if results[1].LatestDurationMS != 5678000 || results[1].LatestPublishedAt == 0 {
		t.Fatalf("unexpected second result: %+v", results[1])
	}

	cached := []PodcastResult{{ID: "101"}, {ID: "202"}}
	if err := client.EnrichLatestEpisodes(cached); err != nil {
		t.Fatalf("cached EnrichLatestEpisodes failed: %v", err)
	}
	if calls.Load() != 1 || cached[0].LatestDurationMS != 1234000 {
		t.Fatalf("lookup was not cached: calls=%d result=%+v", calls.Load(), cached[0])
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

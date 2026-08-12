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

func TestParseExplicitness(t *testing.T) {
	tests := []struct {
		name   string
		values []string
		want   *bool
	}{
		{"explicit", []string{"Explicit"}, boolPointer(true)},
		{"cleaned", []string{"cleaned"}, boolPointer(false)},
		{"not explicit", []string{"notExplicit"}, boolPointer(false)},
		{"clean advisory", []string{"", "Clean"}, boolPointer(false)},
		{"unknown", []string{"", "unrated"}, nil},
		{"explicit wins", []string{"Clean", "explicit"}, boolPointer(true)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := parseExplicitness(tt.values...)
			if tt.want == nil {
				if got != nil {
					t.Fatalf("got %v, want unknown", *got)
				}
				return
			}
			if got == nil || *got != *tt.want {
				t.Fatalf("got %v, want %v", got, *tt.want)
			}
		})
	}
}

func TestSearchPodcastsCleanRequestAndDefensiveFilter(t *testing.T) {
	client := NewITunesClientWithHTTPClient(&http.Client{
		Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
			if req.URL.Query().Get("explicit") != "No" {
				t.Fatalf("explicit query = %q, want No", req.URL.Query().Get("explicit"))
			}
			body := `{"results":[
				{"trackId":1,"trackName":"Explicit","feedUrl":"https://example.com/e","collectionExplicitness":"explicit"},
				{"trackId":2,"trackName":"Clean","feedUrl":"https://example.com/c","trackExplicitness":"notExplicit"},
				{"trackId":3,"trackName":"Unknown","feedUrl":"https://example.com/u"}
			]}`
			return &http.Response{
				StatusCode: http.StatusOK,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader(body)),
				Request:    req,
			}, nil
		}),
	})
	results, err := client.SearchPodcastsWithCountryExplicit("test", "us", 10, false)
	if err != nil {
		t.Fatalf("search failed: %v", err)
	}
	if len(results) != 2 || results[0].Title != "Clean" || results[1].Title != "Unknown" {
		t.Fatalf("unexpected filtered results: %+v", results)
	}
}

func boolPointer(value bool) *bool { return &value }

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

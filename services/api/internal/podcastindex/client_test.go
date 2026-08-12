package podcastindex

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

func TestPodcastIndexClient_IsConfigured(t *testing.T) {
	c1 := NewClient("", "")
	if c1.IsConfigured() {
		t.Errorf("expected IsConfigured to be false for empty keys")
	}

	c2 := NewClient("key123", "sec123")
	if !c2.IsConfigured() {
		t.Errorf("expected IsConfigured to be true when keys are provided")
	}
}

func TestPodcastIndexClient_Search(t *testing.T) {
	mockResponse := `{
		"status": "true",
		"feeds": [
			{
				"id": 100,
				"title": "Test Podcast",
				"url": "http://example.com/rss.xml",
				"author": "Test Author",
				"artwork": "http://example.com/art.jpg",
				"description": "Test Desc"
			}
		],
		"count": 1
	}`

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("User-Agent") == "" || r.Header.Get("X-Auth-Key") == "" {
			t.Errorf("missing expected headers in Podcast Index request")
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(mockResponse))
	}))
	defer ts.Close()

	client := NewClient("test-key", "test-secret")
	client.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})
	client.baseURL = ts.URL

	results, err := client.Search("test")
	if err != nil {
		t.Fatalf("Search failed: %v", err)
	}

	if len(results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(results))
	}
	if results[0].Title != "Test Podcast" {
		t.Errorf("expected title 'Test Podcast', got '%s'", results[0].Title)
	}
}

func TestPodcastIndexClient_ExplicitValuesAndCleanSearch(t *testing.T) {
	mockResponse := `{"feeds":[
		{"id":1,"title":"number one","url":"https://example.com/1","explicit":1},
		{"id":2,"title":"number zero","url":"https://example.com/2","explicit":0},
		{"id":3,"title":"boolean","url":"https://example.com/3","explicit":true},
		{"id":4,"title":"string","url":"https://example.com/4","explicit":"false"},
		{"id":5,"title":"missing","url":"https://example.com/5"},
		{"id":6,"title":"invalid","url":"https://example.com/6","explicit":"sometimes"}
	]}`

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if _, ok := r.URL.Query()["clean"]; !ok {
			t.Error("clean-only search did not send the Podcast Index clean parameter")
		}
		_, _ = w.Write([]byte(mockResponse))
	}))
	defer ts.Close()

	client := NewClient("test-key", "test-secret")
	client.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})
	client.baseURL = ts.URL
	results, err := client.SearchWithExplicit("test", false)
	if err != nil {
		t.Fatalf("SearchWithExplicit failed: %v", err)
	}
	if len(results) != 6 {
		t.Fatalf("got %d results, want 6", len(results))
	}
	want := []*bool{boolPtr(true), boolPtr(false), boolPtr(true), boolPtr(false), nil, nil}
	for i := range want {
		got := results[i].Explicit.Value
		if want[i] == nil {
			if got != nil {
				t.Errorf("result %d explicit = %v, want unknown", i, *got)
			}
		} else if got == nil || *got != *want[i] {
			t.Errorf("result %d explicit = %v, want %v", i, got, *want[i])
		}
	}
}

func boolPtr(value bool) *bool { return &value }

func TestSearchResult_CategoryList(t *testing.T) {
	tests := []struct {
		name       string
		categories map[string]string
		want       []string
	}{
		{
			name:       "nil categories",
			categories: nil,
			want:       nil,
		},
		{
			name:       "empty categories",
			categories: map[string]string{},
			want:       nil,
		},
		{
			name: "valid categories",
			categories: map[string]string{
				"102": "Technology",
				"105": "Science",
			},
			want: []string{"Technology", "Science"}, // ordered by category id: 102, 105
		},
		{
			name: "ignore empty values",
			categories: map[string]string{
				"102": "Technology",
				"103": "",
			},
			want: []string{"Technology"},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			res := SearchResult{Categories: tt.categories}
			got := res.CategoryList()
			if len(got) != len(tt.want) {
				t.Fatalf("CategoryList() returned %d items, want %d", len(got), len(tt.want))
			}
			for i, v := range got {
				if v != tt.want[i] {
					t.Errorf("CategoryList()[%d] = %q, want %q", i, v, tt.want[i])
				}
			}
		})
	}
}

// CategoryList must be deterministic. Go randomizes map iteration, so the
// original positional assertions above passed or failed by luck depending on
// the run — this pins the contract by checking many iterations agree.
func TestSearchResult_CategoryList_IsDeterministic(t *testing.T) {
	res := SearchResult{Categories: map[string]string{
		"102": "Technology",
		"9":   "Business",
		"105": "Science",
		"55":  "News",
	}}

	// Ordered by numeric category id, not by map iteration order.
	want := []string{"Business", "News", "Technology", "Science"}

	for i := 0; i < 100; i++ {
		got := res.CategoryList()
		if len(got) != len(want) {
			t.Fatalf("iteration %d: got %d items, want %d", i, len(got), len(want))
		}
		for j := range want {
			if got[j] != want[j] {
				t.Fatalf("iteration %d: CategoryList()[%d] = %q, want %q", i, j, got[j], want[j])
			}
		}
	}
}

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
			want: []string{"Technology", "Science"},
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


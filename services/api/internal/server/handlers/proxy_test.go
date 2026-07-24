package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

func TestProxyHandler_GetChapters(t *testing.T) {
	mockChapters := `{
		"version": "1.2.0",
		"chapters": [
			{ "startTime": 0, "title": "Introduction" },
			{ "startTime": 120, "title": "Main Topic", "img": "http://example.com/topic.jpg" }
		]
	}`

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(mockChapters))
	}))
	defer ts.Close()

	proxy := NewProxyHandler()
	proxy.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/proxy/chapters?url="+ts.URL, nil)
	rec := httptest.NewRecorder()

	proxy.GetChapters(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var resp struct {
		Chapters []ChapterItem `json:"chapters"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode chapters response: %v", err)
	}

	if len(resp.Chapters) != 2 {
		t.Fatalf("expected 2 chapters, got %d", len(resp.Chapters))
	}
	if resp.Chapters[1].Title != "Main Topic" {
		t.Errorf("expected chapter title 'Main Topic', got %q", resp.Chapters[1].Title)
	}
}

func TestProxyHandler_GetTranscript_WebVTT(t *testing.T) {
	mockVTT := `WEBVTT

00:00:01.000 --> 00:00:05.000
Welcome to KoalaCast!

00:00:05.500 --> 00:00:10.000
Enjoy your podcast experience.
`

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/vtt")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(mockVTT))
	}))
	defer ts.Close()

	proxy := NewProxyHandler()
	proxy.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/proxy/transcript?url="+ts.URL, nil)
	rec := httptest.NewRecorder()

	proxy.GetTranscript(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var resp struct {
		Cues []TranscriptCue `json:"cues"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode transcript response: %v", err)
	}

	if len(resp.Cues) != 2 {
		t.Fatalf("expected 2 cues, got %d", len(resp.Cues))
	}
	if resp.Cues[0].Text != "Welcome to KoalaCast!" {
		t.Errorf("expected first cue text 'Welcome to KoalaCast!', got %q", resp.Cues[0].Text)
	}
}

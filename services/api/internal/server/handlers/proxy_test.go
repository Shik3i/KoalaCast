package handlers

import (
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"strings"
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
		Cues []CueItem `json:"cues"`
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

// TestProxyHandler_BlocksLoopbackByDefault verifies the production handler (whose
// client is built by NewProxyHandler, i.e. AllowLoopback=false) refuses to fetch a
// loopback/private target — the SSRF guard that must not regress.
func TestProxyHandler_BlocksLoopbackByDefault(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"chapters":[{"startTime":0,"title":"secret"}]}`))
	}))
	defer ts.Close()

	// Note: no override of proxy.httpClient — exercise the real production client.
	proxy := NewProxyHandler()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/proxy/chapters?url="+ts.URL, nil)
	rec := httptest.NewRecorder()
	proxy.GetChapters(rec, req)

	if rec.Code == http.StatusOK {
		t.Fatalf("expected loopback fetch to be blocked, but got 200 with body %q", rec.Body.String())
	}
	if rec.Code != http.StatusBadGateway {
		t.Errorf("expected 502 Bad Gateway for blocked target, got %d", rec.Code)
	}
}

func TestProxyHandler_GetImageProxy(t *testing.T) {
	img := image.NewRGBA(image.Rect(0, 0, 100, 100))
	for y := 0; y < 100; y++ {
		for x := 0; x < 100; x++ {
			img.Set(x, y, color.RGBA{R: 255, G: 0, B: 0, A: 255})
		}
	}

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/jpeg")
		w.WriteHeader(http.StatusOK)
		_ = jpeg.Encode(w, img, nil)
	}))
	defer ts.Close()

	proxy := NewProxyHandler()
	proxy.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/proxy/image?url="+ts.URL+"&w=50", nil)
	rec := httptest.NewRecorder()

	proxy.GetImageProxy(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	if contentType := rec.Header().Get("Content-Type"); contentType != "image/jpeg" {
		t.Errorf("expected Content-Type image/jpeg, got %q", contentType)
	}
	if cacheCtrl := rec.Header().Get("Cache-Control"); !strings.Contains(cacheCtrl, "max-age=") {
		t.Errorf("expected Cache-Control header, got %q", cacheCtrl)
	}

	// Test cache hit on second request
	rec2 := httptest.NewRecorder()
	proxy.GetImageProxy(rec2, req)
	if rec2.Code != http.StatusOK {
		t.Fatalf("expected cached hit status 200, got %d", rec2.Code)
	}
}

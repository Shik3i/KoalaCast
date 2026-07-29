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

	proxy := NewProxyHandler(false)
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

	proxy := NewProxyHandler(false)
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

func TestProxyHandler_GetAudioProxy(t *testing.T) {
	audio := []byte("ID3 podcast audio")
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "audio/mpeg")
		w.Header().Set("Accept-Ranges", "bytes")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(audio)
	}))
	defer ts.Close()

	proxy := NewProxyHandler(true)
	proxy.streamClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{
		AllowLoopback:         true,
		DisableRequestTimeout: true,
	})
	req := httptest.NewRequest(http.MethodGet, "/api/v1/proxy/audio?url="+ts.URL, nil)
	rec := httptest.NewRecorder()
	proxy.GetAudioProxy(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	if got := rec.Header().Get("Content-Type"); got != "audio/mpeg" {
		t.Fatalf("expected audio/mpeg, got %q", got)
	}
	if got := rec.Body.String(); got != string(audio) {
		t.Fatalf("expected proxied audio %q, got %q", audio, got)
	}
}

func TestProxyHandler_GetAudioProxyDisabled(t *testing.T) {
	proxy := NewProxyHandler(false)
	req := httptest.NewRequest(
		http.MethodGet,
		"/api/v1/proxy/audio?url=https://cdn.example/episode.mp3",
		nil,
	)
	rec := httptest.NewRecorder()

	proxy.GetAudioProxy(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected disabled proxy to return 403, got %d", rec.Code)
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
	proxy := NewProxyHandler(false)

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

	proxy := NewProxyHandler(false)
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

func TestProxyHandler_GetImageProxyReturnsFallbackWhenUpstreamFails(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
	}))
	defer ts.Close()

	proxy := NewProxyHandler(false)
	proxy.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/proxy/image?url="+ts.URL, nil)
	rec := httptest.NewRecorder()
	proxy.GetImageProxy(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected graceful fallback status 200, got %d", rec.Code)
	}
	if contentType := rec.Header().Get("Content-Type"); contentType != "image/webp" {
		t.Errorf("expected fallback Content-Type image/webp, got %q", contentType)
	}
	if fallback := rec.Header().Get("X-KoalaCast-Image-Fallback"); fallback != "true" {
		t.Errorf("expected fallback response marker, got %q", fallback)
	}
	if cacheControl := rec.Header().Get("Cache-Control"); cacheControl != "no-store" {
		t.Errorf("expected transient fallback to be non-cacheable, got %q", cacheControl)
	}
	if rec.Body.Len() < 1000 {
		t.Errorf("expected embedded WebP fallback body, got %d bytes", rec.Body.Len())
	}
}

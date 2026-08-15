package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

func TestAudioRangeValidationRejectsMultipleAndOversizedRanges(t *testing.T) {
	for _, value := range []string{
		"bytes=0-1,4-5",
		"bytes=-0",
		"bytes=2147483648-",
		"bytes=0-2147483648",
		"bytes=999-1",
	} {
		if validAudioRange(value) {
			t.Errorf("expected invalid range %q to be rejected", value)
		}
	}
	for _, value := range []string{"bytes=0-", "bytes=-1024", "bytes=10-20"} {
		if !validAudioRange(value) {
			t.Errorf("expected range %q to be accepted", value)
		}
	}
}

func TestReadLimitedBodyRejectsOneByteOverLimit(t *testing.T) {
	if _, err := readLimitedBody(bytes.NewReader([]byte("12345")), 4); err == nil {
		t.Fatal("expected oversized response to be rejected")
	}
	body, err := readLimitedBody(bytes.NewReader([]byte("1234")), 4)
	if err != nil || string(body) != "1234" {
		t.Fatalf("expected exact-limit response, got body=%q err=%v", body, err)
	}
}

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

// A tracker prefix that blocks CORS in front of a CDN that allows it is the
// common shape of a podcast enclosure, and the browser cannot follow that chain
// itself: a failed CORS request tells it nothing about where it landed. Resolving
// it here is what lets the listener's audio keep streaming from the publisher
// instead of being pulled through this instance.
func TestGetAudioResolveFollowsRedirectAndReportsCORS(t *testing.T) {
	const browserOrigin = "https://cast.example"

	cdn := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Range"); got != "bytes=0-0" {
			t.Errorf("expected a ranged probe, got Range=%q", got)
		}
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Content-Type", "audio/mpeg")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write([]byte("x"))
	}))
	defer cdn.Close()

	tracker := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Deliberately no Access-Control-Allow-Origin, like a real prefix tracker.
		http.Redirect(w, r, cdn.URL+"/episode.mp3", http.StatusFound)
	}))
	defer tracker.Close()

	proxy := NewProxyHandler(false)
	proxy.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})
	// The safe client's redirect policy rejects loopback literals outright, which
	// is right in production and impossible to test against httptest. Relax it for
	// the hop only; SSRF behaviour has its own tests in the rss package.
	proxy.httpClient.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if len(via) >= 5 {
			return fmt.Errorf("too many redirects")
		}
		return nil
	}

	req := httptest.NewRequest(http.MethodGet,
		"/api/v1/proxy/audio/resolve?url="+tracker.URL+"/redirect.mp3&origin="+browserOrigin, nil)
	rec := httptest.NewRecorder()

	proxy.GetAudioResolve(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	var resp audioResolveResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.URL != cdn.URL+"/episode.mp3" {
		t.Errorf("expected the CDN URL, got %q", resp.URL)
	}
	if !resp.Redirected {
		t.Error("expected redirected=true")
	}
	if !resp.CORSAllowed {
		t.Error("expected cors_allowed=true for a wildcard Access-Control-Allow-Origin")
	}
}

// A host that allows nobody must be reported as such, so the client falls back to
// the proxy rather than loading a source Web Audio will silently mute.
func TestGetAudioResolveReportsMissingCORS(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write([]byte("x"))
	}))
	defer upstream.Close()

	proxy := NewProxyHandler(false)
	proxy.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})

	req := httptest.NewRequest(http.MethodGet,
		"/api/v1/proxy/audio/resolve?url="+upstream.URL+"/a.mp3&origin=https://cast.example", nil)
	rec := httptest.NewRecorder()
	proxy.GetAudioResolve(rec, req)

	var resp audioResolveResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.CORSAllowed {
		t.Error("expected cors_allowed=false when the host sends no ACAO header")
	}
}

func TestGetAudioResolveRejectsNonHTTPURL(t *testing.T) {
	proxy := NewProxyHandler(false)
	req := httptest.NewRequest(http.MethodGet, "/api/v1/proxy/audio/resolve?url=file:///etc/passwd", nil)
	rec := httptest.NewRecorder()
	proxy.GetAudioResolve(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for a non-http scheme, got %d", rec.Code)
	}
}

// A CDN that negotiates on Accept gives back exactly what was asked for. The
// proxy used to send a browser's header, AVIF first, and then failed to decode
// the AVIF it had requested — answering with its own placeholder, at 200, for
// artwork that was perfectly fine. Every imgix/Cloudinary `auto=format` cover in
// the app was a grey rectangle because of it.
func TestProxyHandler_ImageAcceptOffersOnlyDecodableFormats(t *testing.T) {
	for _, unsupported := range []string{"image/avif", "image/svg+xml", "image/heic", "image/jxl"} {
		if strings.Contains(acceptedImageFormats, unsupported) {
			t.Errorf("Accept advertises %s, which this build cannot decode: %q", unsupported, acceptedImageFormats)
		}
	}
	for _, supported := range []string{"image/webp", "image/jpeg", "image/png"} {
		if !strings.Contains(acceptedImageFormats, supported) {
			t.Errorf("Accept omits %s, which this build can decode: %q", supported, acceptedImageFormats)
		}
	}
}

// The header has to survive the trip, not just be well-formed: an upstream that
// varies on Accept must see it.
func TestProxyHandler_ImageRequestSendsNegotiatedAccept(t *testing.T) {
	img := image.NewRGBA(image.Rect(0, 0, 10, 10))
	var seenAccept string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenAccept = r.Header.Get("Accept")
		w.Header().Set("Content-Type", "image/jpeg")
		w.WriteHeader(http.StatusOK)
		_ = jpeg.Encode(w, img, nil)
	}))
	defer ts.Close()

	proxy := NewProxyHandler(false)
	proxy.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})
	rec := httptest.NewRecorder()
	proxy.GetImageProxy(rec, httptest.NewRequest(http.MethodGet, "/api/v1/proxy/image?url="+ts.URL+"&w=5", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if rec.Header().Get("X-KoalaCast-Image-Fallback") == "true" {
		t.Fatal("a decodable upstream image was replaced by the placeholder")
	}
	if seenAccept != acceptedImageFormats {
		t.Fatalf("upstream saw Accept %q, want %q", seenAccept, acceptedImageFormats)
	}
}

// Both proxy endpoints take a URL straight out of a publisher's feed. The
// dialer resolves and vets every address, but it never looks at the userinfo
// component, so credentials embedded in an artwork or enclosure URL would be
// presented upstream by the instance itself.
func TestProxyHandler_RejectsEmbeddedCredentials(t *testing.T) {
	proxy := NewProxyHandler(true)
	proxy.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})
	proxy.streamClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{AllowLoopback: true})

	const hostile = "https://user:secret@cdn.example/cover.jpg"
	for name, call := range map[string]func(http.ResponseWriter, *http.Request){
		"image": proxy.GetImageProxy,
		"audio": proxy.GetAudioProxy,
	} {
		rec := httptest.NewRecorder()
		call(rec, httptest.NewRequest(http.MethodGet, "/p?url="+url.QueryEscape(hostile), nil))
		if rec.Code != http.StatusBadRequest {
			t.Errorf("%s proxy accepted embedded credentials: got %d, want 400", name, rec.Code)
		}
	}
}

// The loopback targets the test transport is allowed to reach must keep working:
// address policy belongs to the dialer, not to this check.
func TestProxyTargetAllowedLeavesAddressPolicyToTheDialer(t *testing.T) {
	if !proxyTargetAllowed("http://127.0.0.1:8080/cover.jpg") {
		t.Error("a loopback target must not be rejected here")
	}
	if proxyTargetAllowed("https://user@host/x") {
		t.Error("userinfo must be rejected")
	}
	if proxyTargetAllowed("https:///no-host") {
		t.Error("a missing host must be rejected")
	}
}

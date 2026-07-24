package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestClientHostIP_StripsPort(t *testing.T) {
	if got := ClientHostIP("192.168.1.50:54321"); got != "192.168.1.50" {
		t.Errorf("expected 192.168.1.50, got %q", got)
	}
	if got := ClientHostIP("10.0.0.1"); got != "10.0.0.1" {
		t.Errorf("expected passthrough for bare IP, got %q", got)
	}
}

func TestRealIP_TrustedProxyRewritesFromXFF(t *testing.T) {
	var seen string
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = r.RemoteAddr
	})
	// Peer is inside the trusted CIDR -> XFF client IP is honored.
	h := RealIP([]string{"172.16.0.0/12"})(next)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "172.20.0.5:40000"
	req.Header.Set("X-Forwarded-For", "203.0.113.9")
	h.ServeHTTP(httptest.NewRecorder(), req)

	if seen != "203.0.113.9" {
		t.Errorf("expected RemoteAddr rewritten to client IP 203.0.113.9, got %q", seen)
	}
}

func TestRealIP_UntrustedPeerIgnoresXFF(t *testing.T) {
	var seen string
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = r.RemoteAddr
	})
	// Peer is NOT trusted -> spoofed XFF must be ignored.
	h := RealIP([]string{"127.0.0.1"})(next)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "198.51.100.7:40000"
	req.Header.Set("X-Forwarded-For", "10.0.0.1")
	h.ServeHTTP(httptest.NewRecorder(), req)

	if seen != "198.51.100.7:40000" {
		t.Errorf("expected untrusted RemoteAddr unchanged, got %q", seen)
	}
}

// TestRateLimiter_SameIPDifferentPorts proves the limiter keys on the host IP,
// not host:port — otherwise real clients (whose source port changes per
// connection) would never be throttled.
func TestRateLimiter_SameIPDifferentPorts(t *testing.T) {
	limiter := NewRateLimiter(2, 1*time.Minute)
	handler := limiter.Limit(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	ports := []string{"1001", "2002", "3003"}
	codes := make([]int, 0, len(ports))
	for _, p := range ports {
		req := httptest.NewRequest(http.MethodPost, "/login", nil)
		req.RemoteAddr = "203.0.113.44:" + p
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		codes = append(codes, rec.Code)
	}

	if codes[0] != http.StatusOK || codes[1] != http.StatusOK {
		t.Fatalf("expected first two requests OK, got %v", codes)
	}
	if codes[2] != http.StatusTooManyRequests {
		t.Errorf("expected 3rd request from same IP (diff port) to be 429, got %d", codes[2])
	}
}

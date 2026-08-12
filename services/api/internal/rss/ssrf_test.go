package rss

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestReadResponseBodyDetectsLimitInsteadOfReturningTruncatedXML(t *testing.T) {
	payload := strings.Repeat("x", 129)
	_, err := ReadResponseBody(strings.NewReader(payload), 128)
	if !errors.Is(err, ErrResponseTooLarge) {
		t.Fatalf("expected ErrResponseTooLarge, got %v", err)
	}

	withinLimit, err := ReadResponseBody(strings.NewReader(payload[:128]), 128)
	if err != nil || len(withinLimit) != 128 {
		t.Fatalf("exact-limit response failed: bytes=%d err=%v", len(withinLimit), err)
	}
}

type mockResolver struct {
	hosts map[string][]net.IP
}

func (m *mockResolver) LookupIP(ctx context.Context, network, host string) ([]net.IP, error) {
	if ips, ok := m.hosts[host]; ok {
		return ips, nil
	}
	return nil, fmt.Errorf("unknown host: %s", host)
}

type mockDialer struct{}

func (m *mockDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	// Dummy connection simulation
	return nil, fmt.Errorf("mock connection refused for test: %s", address)
}

func TestIsIPBlocked_ExtendedRanges(t *testing.T) {
	tests := []struct {
		ip       string
		expected bool
	}{
		// IPv4 Private & Special
		{"127.0.0.1", true},
		{"10.0.0.1", true},
		{"172.16.0.1", true},
		{"192.168.1.1", true},
		{"169.254.169.254", true},
		{"100.64.0.1", true},
		{"0.0.0.0", true},
		{"192.0.2.1", true},

		// IPv6 Private & Special
		{"::1", true},
		{"fc00::1", true},
		{"fe80::1", true},
		{"::", true},

		// IPv4-Mapped IPv6
		{"::ffff:127.0.0.1", true},
		{"::ffff:10.0.0.1", true},
		{"::ffff:192.168.1.1", true},

		// Valid Public IPv4 & IPv6
		{"8.8.8.8", false},
		{"1.1.1.1", false},
		{"2607:f8b0:4005:805::200e", false},
	}

	for _, tt := range tests {
		ip := net.ParseIP(tt.ip)
		got := IsIPBlocked(ip)
		if got != tt.expected {
			t.Errorf("IsIPBlocked(%s) = %v, expected %v", tt.ip, got, tt.expected)
		}
	}
}

func TestValidateURL(t *testing.T) {
	tests := []struct {
		url     string
		wantErr bool
	}{
		{"https://example.com/feed.xml", false},
		{"http://podcast.org/rss", false},
		{"ftp://example.com/feed.xml", true},
		{"file:///etc/passwd", true},
		{"http://user:pass@example.com/feed.xml", true},
		{"http://127.0.0.1/feed.xml", true},
		{"http://169.254.169.254/latest/meta-data/", true},
	}

	for _, tt := range tests {
		err := ValidateURL(tt.url)
		if (err != nil) != tt.wantErr {
			t.Errorf("ValidateURL(%s) error = %v, wantErr %v", tt.url, err, tt.wantErr)
		}
	}
}

func TestSafeHTTPClient_BlockedDNS(t *testing.T) {
	resolver := &mockResolver{
		hosts: map[string][]net.IP{
			"malicious.local": {net.ParseIP("127.0.0.1")},
			"mixed.local":     {net.ParseIP("93.184.216.34"), net.ParseIP("10.0.0.1")},
		},
	}

	client := NewSafeHTTPClient(SafeTransportConfig{
		Resolver: resolver,
		Dialer:   &mockDialer{},
	})

	// Test 1: Host resolves to 127.0.0.1
	req, _ := http.NewRequest(http.MethodGet, "http://malicious.local/rss", nil)
	_, err := client.Do(req)
	if err == nil {
		t.Errorf("expected error connecting to malicious.local, got nil")
	}

	// Test 2: Host resolves to mixed IPs (public + private) -> Must be blocked
	req2, _ := http.NewRequest(http.MethodGet, "http://mixed.local/rss", nil)
	_, err2 := client.Do(req2)
	if err2 == nil {
		t.Errorf("expected error connecting to mixed.local, got nil")
	}
}

func TestSafeHTTPClient_RedirectToBlocked(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "http://127.0.0.1/secret", http.StatusFound)
	}))
	defer ts.Close()

	client := NewSafeHTTPClient(SafeTransportConfig{
		ConnectTimeout: 2 * time.Second,
	})

	req, _ := http.NewRequest(http.MethodGet, ts.URL, nil)
	_, err := client.Do(req)
	if err == nil {
		t.Errorf("expected error when redirecting to 127.0.0.1, got nil")
	}
}

func TestSafeHTTPClient_ExcessiveRedirects(t *testing.T) {
	var ts *httptest.Server
	ts = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, ts.URL, http.StatusFound)
	}))
	defer ts.Close()

	client := NewSafeHTTPClient(SafeTransportConfig{
		ConnectTimeout: 2 * time.Second,
	})

	req, _ := http.NewRequest(http.MethodGet, ts.URL, nil)
	_, err := client.Do(req)
	if err == nil {
		t.Errorf("expected error on excessive redirects, got nil")
	}
}

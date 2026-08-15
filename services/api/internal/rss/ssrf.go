package rss

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

var blockedCIDRs = []string{
	"0.0.0.0/8",       // Current network
	"127.0.0.0/8",     // IPv4 Loopback
	"10.0.0.0/8",      // Private Class A
	"172.16.0.0/12",   // Private Class B
	"192.168.0.0/16",  // Private Class C
	"169.254.0.0/16",  // Link Local / Cloud Metadata
	"100.64.0.0/10",   // Carrier-Grade NAT
	"192.0.0.0/24",    // IETF Protocol Assignments (incl. NAT64 well-known prefix)
	"198.18.0.0/15",   // Benchmarking (RFC 2544)
	"192.0.2.0/24",    // TEST-NET-1
	"198.51.100.0/24", // TEST-NET-2
	"203.0.113.0/24",  // TEST-NET-3
	"224.0.0.0/4",     // Multicast
	"240.0.0.0/4",     // Reserved
	"::1/128",         // IPv6 Loopback
	"::/128",          // IPv6 Unspecified
	"fc00::/7",        // IPv6 Unique Local
	"fe80::/10",       // IPv6 Link Local
	"ff00::/8",        // IPv6 Multicast
	"2001:db8::/32",   // IPv6 Documentation
	// NAT64 maps an IPv4 address into IPv6, so on a network that runs one
	// `64:ff9b::7f00:1` is a route to 127.0.0.1 that To4() does not unwrap and
	// none of the IPv4 rules above can see.
	"64:ff9b::/96",
	"64:ff9b:1::/48",
}

var blockedNets []*net.IPNet

func init() {
	for _, cidr := range blockedCIDRs {
		_, ipNet, err := net.ParseCIDR(cidr)
		if err == nil {
			blockedNets = append(blockedNets, ipNet)
		}
	}
}

// IsIPBlocked returns true if an IP address (IPv4, IPv6, or IPv4-mapped IPv6) is restricted.
func IsIPBlocked(ip net.IP) bool {
	if ip == nil {
		return true
	}

	// Convert IPv4-mapped IPv6 (e.g. ::ffff:10.0.0.1) to standard 4-byte IPv4
	if ip4 := ip.To4(); ip4 != nil {
		ip = ip4
	}

	for _, ipNet := range blockedNets {
		if ipNet.Contains(ip) {
			return true
		}
	}
	return false
}

// HostResolver interface allows injecting custom DNS resolvers for testing.
type HostResolver interface {
	LookupIP(ctx context.Context, network, host string) ([]net.IP, error)
}

// NetworkDialer interface allows injecting custom dialers for testing.
type NetworkDialer interface {
	DialContext(ctx context.Context, network, address string) (net.Conn, error)
}

// SafeTransportConfig configures the SSRF-safe HTTP Transport.
type SafeTransportConfig struct {
	Resolver        HostResolver
	Dialer          NetworkDialer
	ConnectTimeout  time.Duration
	ResponseTimeout time.Duration
	// DisableRequestTimeout keeps the SSRF-safe connect/TLS/header deadlines but
	// removes the whole-request deadline needed for long-lived audio streams.
	DisableRequestTimeout bool
	AllowLoopback         bool // Set true ONLY in unit tests targeting httptest.NewServer
}

// NewSafeHTTPClient creates a secure http.Client with custom DialContext that validates DNS resolution at connect time.
func NewSafeHTTPClient(cfg SafeTransportConfig) *http.Client {
	if cfg.Resolver == nil {
		cfg.Resolver = net.DefaultResolver
	}
	if cfg.Dialer == nil {
		cfg.Dialer = &net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}
	}
	if cfg.ConnectTimeout == 0 {
		cfg.ConnectTimeout = 10 * time.Second
	}
	if cfg.ResponseTimeout == 0 {
		cfg.ResponseTimeout = 15 * time.Second
	}

	transport := &http.Transport{
		ResponseHeaderTimeout: cfg.ResponseTimeout,
		TLSHandshakeTimeout:   10 * time.Second,
		IdleConnTimeout:       30 * time.Second,
		MaxIdleConns:          20,
		DisableKeepAlives:     true,
	}

	transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, fmt.Errorf("invalid address format: %w", err)
		}

		// If host is an IP literal, validate directly
		if ip := net.ParseIP(host); ip != nil {
			if !cfg.AllowLoopback && IsIPBlocked(ip) {
				return nil, fmt.Errorf("connection to restricted IP address blocked: %s", ip.String())
			}
			return cfg.Dialer.DialContext(ctx, network, addr)
		}

		// Resolve host IPs dynamically inside DialContext
		ips, err := cfg.Resolver.LookupIP(ctx, "ip", host)
		if err != nil {
			return nil, fmt.Errorf("failed to resolve host %s: %w", host, err)
		}

		if len(ips) == 0 {
			return nil, fmt.Errorf("no IP addresses returned for host %s", host)
		}

		// Validate ALL resolved IP addresses for the hostname
		for _, ip := range ips {
			if !cfg.AllowLoopback && IsIPBlocked(ip) {
				return nil, fmt.Errorf("host %s resolved to restricted IP %s", host, ip.String())
			}
		}

		// Connect directly to the first validated IP
		targetAddr := net.JoinHostPort(ips[0].String(), port)
		return cfg.Dialer.DialContext(ctx, network, targetAddr)
	}

	transport.TLSClientConfig = &tls.Config{
		MinVersion: tls.VersionTLS12,
	}

	requestTimeout := cfg.ResponseTimeout
	if cfg.DisableRequestTimeout {
		requestTimeout = 0
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   requestTimeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return fmt.Errorf("too many redirects (max 5)")
			}
			return ValidateURL(req.URL.String())
		},
	}

	return client
}

// ValidateURL verifies scheme, embedded credentials, and syntax before initiating a request.
func ValidateURL(rawURL string) error {
	u, err := url.Parse(rawURL)
	if err != nil {
		return fmt.Errorf("invalid URL syntax: %w", err)
	}

	if !strings.EqualFold(u.Scheme, "http") && !strings.EqualFold(u.Scheme, "https") {
		return fmt.Errorf("unsupported URL scheme: %s (only http and https allowed)", u.Scheme)
	}

	if u.User != nil {
		return fmt.Errorf("embedded URL credentials are not allowed")
	}

	hostname := u.Hostname()
	if hostname == "" {
		return fmt.Errorf("missing hostname in URL")
	}

	// If host is an explicit IP literal, check immediately
	if ip := net.ParseIP(hostname); ip != nil {
		if IsIPBlocked(ip) {
			return fmt.Errorf("restricted IP address target: %s", hostname)
		}
	}

	return nil
}

// LimitedReadCloser wraps an io.ReadCloser with an io.LimitReader to cap maximum response body bytes.
type LimitedReadCloser struct {
	io.Reader
	io.Closer
}

func LimitResponseBody(body io.ReadCloser, maxBytes int64) io.ReadCloser {
	return &LimitedReadCloser{
		Reader: io.LimitReader(body, maxBytes),
		Closer: body,
	}
}

var ErrResponseTooLarge = errors.New("response body exceeds configured byte limit")

// ReadResponseBody reads at most maxBytes and deliberately probes one byte past
// the boundary. io.LimitReader alone makes a truncated XML document look like a
// malformed publisher feed, hiding the actual operational cause.
func ReadResponseBody(body io.Reader, maxBytes int64) ([]byte, error) {
	if maxBytes <= 0 {
		return nil, ErrResponseTooLarge
	}
	payload, err := io.ReadAll(io.LimitReader(body, maxBytes+1))
	if err != nil {
		return nil, err
	}
	if int64(len(payload)) > maxBytes {
		return nil, ErrResponseTooLarge
	}
	return payload, nil
}

package middleware

import (
	"net"
	"net/http"
	"strings"
)

// ClientHostIP extracts the bare host IP from a "host:port" RemoteAddr. If the
// value has no port (already just an IP, e.g. after RealIP rewrote it), it is
// returned as-is. Never returns "host:port" so callers can safely use it as a
// stable per-client key.
func ClientHostIP(remoteAddr string) string {
	if host, _, err := net.SplitHostPort(remoteAddr); err == nil {
		return host
	}
	return remoteAddr
}

// proxyMatcher tests whether an IP is a trusted proxy. Entries may be exact IPs
// (e.g. "127.0.0.1") or CIDR ranges (e.g. "172.16.0.0/12" for a Docker bridge
// network whose proxy IP is assigned dynamically).
type proxyMatcher struct {
	exact map[string]struct{}
	nets  []*net.IPNet
}

func newProxyMatcher(entries []string) *proxyMatcher {
	m := &proxyMatcher{exact: make(map[string]struct{})}
	for _, e := range entries {
		e = strings.TrimSpace(e)
		if e == "" {
			continue
		}
		if strings.Contains(e, "/") {
			if _, ipNet, err := net.ParseCIDR(e); err == nil {
				m.nets = append(m.nets, ipNet)
			}
			continue
		}
		m.exact[e] = struct{}{}
	}
	return m
}

func (m *proxyMatcher) contains(ipStr string) bool {
	if _, ok := m.exact[ipStr]; ok {
		return true
	}
	if len(m.nets) == 0 {
		return false
	}
	ip := net.ParseIP(ipStr)
	if ip == nil {
		return false
	}
	for _, n := range m.nets {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

// RealIP rewrites r.RemoteAddr to the real client IP when — and only when — the
// direct peer is a configured trusted proxy. This prevents clients from spoofing
// their address via X-Forwarded-For while still recovering the true client IP
// behind a trusted reverse proxy (e.g. Caddy). Untrusted peers keep their
// on-the-wire RemoteAddr, so header spoofing cannot bypass rate limiting.
func RealIP(trustedProxies []string) func(http.Handler) http.Handler {
	matcher := newProxyMatcher(trustedProxies)

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			peer := ClientHostIP(r.RemoteAddr)
			if matcher.contains(peer) {
				if client := clientFromForwardedFor(r.Header.Get("X-Forwarded-For"), matcher); client != "" {
					r.RemoteAddr = client
				} else if xr := strings.TrimSpace(r.Header.Get("X-Real-IP")); xr != "" && net.ParseIP(xr) != nil {
					r.RemoteAddr = xr
				}
			}
			next.ServeHTTP(w, r)
		})
	}
}

// clientFromForwardedFor walks X-Forwarded-For from right (closest proxy) to left
// (original client), skipping addresses that are themselves trusted proxies, and
// returns the first non-trusted, valid IP — the real client.
func clientFromForwardedFor(header string, matcher *proxyMatcher) string {
	if header == "" {
		return ""
	}
	parts := strings.Split(header, ",")
	for i := len(parts) - 1; i >= 0; i-- {
		candidate := strings.TrimSpace(parts[i])
		if candidate == "" || net.ParseIP(candidate) == nil {
			continue
		}
		if matcher.contains(candidate) {
			continue
		}
		return candidate
	}
	return ""
}

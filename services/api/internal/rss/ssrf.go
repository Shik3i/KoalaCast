package rss

import (
	"fmt"
	"net"
	"net/http"
	"net/url"
	"time"
)

var blockedIPNets []*net.IPNet

func init() {
	cidrs := []string{
		"127.0.0.0/8",    // IPv4 Loopback
		"10.0.0.0/8",     // Private Class A
		"172.16.0.0/12",  // Private Class B
		"192.168.0.0/16", // Private Class C
		"169.254.0.0/16", // Link Local / Cloud Metadata
		"0.0.0.0/8",      // Current network
		"::1/128",        // IPv6 Loopback
		"fc00::/7",       // IPv6 Unique Local
		"fe80::/10",      // IPv6 Link Local
	}

	for _, cidr := range cidrs {
		_, ipNet, err := net.ParseCIDR(cidr)
		if err == nil {
			blockedIPNets = append(blockedIPNets, ipNet)
		}
	}
}

// IsIPBlocked checks if an IP address falls into a forbidden private or loopback range.
func IsIPBlocked(ip net.IP) bool {
	if ip == nil {
		return true
	}
	for _, ipNet := range blockedIPNets {
		if ipNet.Contains(ip) {
			return true
		}
	}
	return false
}

// ValidateURL checks if a target URL scheme and host IP address are safe from SSRF exploits.
func ValidateURL(rawURL string) error {
	u, err := url.Parse(rawURL)
	if err != nil {
		return fmt.Errorf("malformed URL: %w", err)
	}

	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("unsupported URL scheme: %s", u.Scheme)
	}

	hostname := u.Hostname()
	if hostname == "" {
		return fmt.Errorf("missing hostname in URL")
	}

	// Resolve hostname IPs
	ips, err := net.LookupIP(hostname)
	if err != nil {
		return fmt.Errorf("failed to resolve hostname %s: %w", hostname, err)
	}

	if len(ips) == 0 {
		return fmt.Errorf("no IP addresses found for hostname %s", hostname)
	}

	for _, ip := range ips {
		if IsIPBlocked(ip) {
			return fmt.Errorf("access to private or internal IP range blocked (%s -> %s)", hostname, ip.String())
		}
	}

	return nil
}

// SafeHTTPClient returns an http.Client with custom redirect logic enforcing SSRF protection at every redirect hop.
func SafeHTTPClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return fmt.Errorf("too many redirects")
			}
			return ValidateURL(req.URL.String())
		},
	}
}

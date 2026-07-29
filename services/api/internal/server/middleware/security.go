package middleware

import (
	"net/http"
	"strings"
)

// SecurityHeaders sets conservative response headers on every API response.
func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cross-Origin-Resource-Policy", "same-origin")
		// Defense-in-depth CSP. The episode view renders publisher-supplied HTML
		// (DOMPurify-sanitized) via {@html}, so lock down the dangerous sinks:
		// no plugins/objects, no framing, no <base> hijack, forms only to self,
		// and scripts restricted to same-origin. 'unsafe-inline' is required
		// for scripts because SvelteKit inlines its hydration bootstrap and the
		// app.html theme snippet, and for styles because the CSS is inlined; images
		// and audio must allow remote http(s) hosts since artwork/enclosures live on
		// third-party podcast CDNs.
		h.Set("Content-Security-Policy", strings.Join([]string{
			"default-src 'self'",
			"base-uri 'self'",
			"object-src 'none'",
			"frame-ancestors 'none'",
			"form-action 'self'",
			"script-src 'self' 'unsafe-inline'",
			"style-src 'self' 'unsafe-inline'",
			"img-src 'self' data: https: http:",
			"media-src 'self' https: http:",
			"font-src 'self'",
			// Downloads, RSS artwork effects and audio analysis use fetch() against
			// publisher CDNs; media-src alone does not authorize those connections.
			"connect-src 'self' https: http:",
		}, "; "))
		next.ServeHTTP(w, r)
	})
}

// CORS enables cross-origin access only for an explicit allowlist of origins.
// When the allowlist is empty the middleware is a no-op (same-origin only),
// which is the default posture for the cookie-authenticated web client.
func CORS(allowedOrigins []string) func(http.Handler) http.Handler {
	allowed := make(map[string]struct{}, len(allowedOrigins))
	for _, o := range allowedOrigins {
		if o = strings.TrimSpace(o); o != "" {
			allowed[o] = struct{}{}
		}
	}

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			if origin != "" && len(allowed) > 0 {
				if _, ok := allowed[origin]; ok {
					h := w.Header()
					h.Set("Access-Control-Allow-Origin", origin)
					h.Set("Access-Control-Allow-Credentials", "true")
					h.Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
					h.Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
					h.Add("Vary", "Origin")
					if r.Method == http.MethodOptions {
						w.WriteHeader(http.StatusNoContent)
						return
					}
				} else if r.Method == http.MethodOptions {
					w.WriteHeader(http.StatusForbidden)
					return
				}
			}
			next.ServeHTTP(w, r)
		})
	}
}

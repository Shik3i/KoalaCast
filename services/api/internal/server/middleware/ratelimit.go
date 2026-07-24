package middleware

import (
	"net/http"
	"sync"
	"time"
)

type RateLimiter struct {
	mu        sync.Mutex
	requests  map[string][]time.Time
	limit     int
	window    time.Duration
	cleanFreq time.Duration
}

func NewRateLimiter(limit int, window time.Duration) *RateLimiter {
	rl := &RateLimiter{
		requests:  make(map[string][]time.Time),
		limit:     limit,
		window:    window,
		cleanFreq: 5 * time.Minute,
	}

	go rl.cleanupLoop()
	return rl
}

func (rl *RateLimiter) Limit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Key on the client host IP only. r.RemoteAddr is "host:port"; keying on the
		// raw value would bucket every ephemeral source port separately, so the limit
		// would almost never trigger for a real client. Strip the port. When a
		// RealIP middleware runs first, r.RemoteAddr already carries the resolved
		// client IP for requests arriving via a trusted proxy.
		ip := ClientHostIP(r.RemoteAddr)

		rl.mu.Lock()
		now := time.Now()
		cutoff := now.Add(-rl.window)

		// Filter timestamps within window
		var valid []time.Time
		for _, t := range rl.requests[ip] {
			if t.After(cutoff) {
				valid = append(valid, t)
			}
		}

		if len(valid) >= rl.limit {
			rl.mu.Unlock()
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Retry-After", "60")
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"error":"too many requests, please slow down"}`))
			return
		}

		valid = append(valid, now)
		rl.requests[ip] = valid
		rl.mu.Unlock()

		next.ServeHTTP(w, r)
	})
}

func (rl *RateLimiter) cleanupLoop() {
	ticker := time.NewTicker(rl.cleanFreq)
	for range ticker.C {
		rl.mu.Lock()
		now := time.Now()
		cutoff := now.Add(-rl.window)

		for ip, timestamps := range rl.requests {
			var valid []time.Time
			for _, t := range timestamps {
				if t.After(cutoff) {
					valid = append(valid, t)
				}
			}
			if len(valid) == 0 {
				delete(rl.requests, ip)
			} else {
				rl.requests[ip] = valid
			}
		}
		rl.mu.Unlock()
	}
}

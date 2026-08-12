package middleware

import (
	"math"
	"net/http"
	"strconv"
	"sync"
	"time"
)

type rateWindow struct {
	count   int
	resetAt time.Time
}

type RateLimiter struct {
	mu        sync.Mutex
	requests  map[string]rateWindow
	limit     int
	window    time.Duration
	cleanFreq time.Duration
}

func NewRateLimiter(limit int, window time.Duration) *RateLimiter {
	rl := &RateLimiter{
		requests:  make(map[string]rateWindow),
		limit:     limit,
		window:    window,
		cleanFreq: 5 * time.Minute,
	}

	go rl.cleanupLoop()
	return rl
}

func (rl *RateLimiter) Limit(next http.Handler) http.Handler {
	return rl.LimitBy(func(r *http.Request) string { return ClientHostIP(r.RemoteAddr) })(next)
}

// LimitAuthenticated keys expensive account operations on the authenticated
// principal instead of the source IP. That keeps one NAT from penalising all
// listeners while still bounding a distributed sync flood for one account.
func (rl *RateLimiter) LimitAuthenticated(next http.Handler) http.Handler {
	return rl.LimitBy(func(r *http.Request) string {
		user := GetAuthUser(r.Context())
		if user == nil {
			return "unauthenticated:" + ClientHostIP(r.RemoteAddr)
		}
		return "user:" + user.ID
	})(next)
}

func (rl *RateLimiter) LimitBy(key func(*http.Request) string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			bucketKey := key(r)

			rl.mu.Lock()
			now := time.Now()
			window := rl.requests[bucketKey]
			if window.resetAt.IsZero() || !now.Before(window.resetAt) {
				window = rateWindow{resetAt: now.Add(rl.window)}
			}

			if window.count >= rl.limit {
				retryAfter := max(1, int(math.Ceil(time.Until(window.resetAt).Seconds())))
				rl.mu.Unlock()
				w.Header().Set("Content-Type", "application/json")
				w.Header().Set("Retry-After", strconv.Itoa(retryAfter))
				w.WriteHeader(http.StatusTooManyRequests)
				_, _ = w.Write([]byte(`{"error":"too many requests, please slow down"}`))
				return
			}

			window.count++
			rl.requests[bucketKey] = window
			rl.mu.Unlock()

			next.ServeHTTP(w, r)
		})
	}
}

func (rl *RateLimiter) cleanupLoop() {
	ticker := time.NewTicker(rl.cleanFreq)
	for range ticker.C {
		rl.mu.Lock()
		now := time.Now()

		for key, window := range rl.requests {
			if !now.Before(window.resetAt) {
				delete(rl.requests, key)
			}
		}
		rl.mu.Unlock()
	}
}

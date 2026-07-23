package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestRateLimiter(t *testing.T) {
	limiter := NewRateLimiter(3, 1*time.Minute)

	handler := limiter.Limit(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	// 3 requests within limit -> 200 OK
	for i := 0; i < 3; i++ {
		req := httptest.NewRequest(http.MethodPost, "/login", nil)
		req.RemoteAddr = "192.168.1.50:12345"
		rec := httptest.NewRecorder()

		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Errorf("request %d expected 200 OK, got %d", i+1, rec.Code)
		}
	}

	// 4th request -> 429 Too Many Requests
	req := httptest.NewRequest(http.MethodPost, "/login", nil)
	req.RemoteAddr = "192.168.1.50:12345"
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("expected 429 Too Many Requests on 4th request, got %d", rec.Code)
	}
}

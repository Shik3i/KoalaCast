package middleware

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCORSPreflightAllowsPut(t *testing.T) {
	handler := CORS([]string{"https://app.example"})(http.HandlerFunc(func(
		w http.ResponseWriter,
		_ *http.Request,
	) {
		w.WriteHeader(http.StatusNoContent)
	}))
	req := httptest.NewRequest(http.MethodOptions, "/api/v1/resource", nil)
	req.Header.Set("Origin", "https://app.example")
	req.Header.Set("Access-Control-Request-Method", http.MethodPut)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if !strings.Contains(rec.Header().Get("Access-Control-Allow-Methods"), http.MethodPut) {
		t.Fatalf(
			"expected PUT in Access-Control-Allow-Methods, got %q",
			rec.Header().Get("Access-Control-Allow-Methods"),
		)
	}
}

func TestSecurityHeadersLeavesStaticCSPToGeneratedMeta(t *testing.T) {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/", nil)
	SecurityHeaders(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {})).
		ServeHTTP(recorder, request)

	csp := recorder.Header().Get("Content-Security-Policy")
	if csp != "" {
		t.Fatalf("static response must not override generated hash CSP, got %q", csp)
	}
}

func TestSecurityHeadersLocksDownAPIResponses(t *testing.T) {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	SecurityHeaders(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {})).
		ServeHTTP(recorder, request)

	csp := recorder.Header().Get("Content-Security-Policy")
	if !strings.Contains(csp, "default-src 'none'") || !strings.Contains(csp, "frame-ancestors 'none'") {
		t.Fatalf("API CSP is not restrictive: %q", csp)
	}
}

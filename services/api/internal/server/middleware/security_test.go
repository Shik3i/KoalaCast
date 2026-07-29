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

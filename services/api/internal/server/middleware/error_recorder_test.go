package middleware

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
)

func TestErrorRecorderPersistsAPIErrorWithoutTruncatingResponse(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "errors.db"), logger)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	message := strings.Repeat("failure-", 700)
	handler := RequestID(ErrorRecorder(database, logger)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ObserveErrorUser(r.Context(), "user-1")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"` + message + `"}`))
	})))
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/sync", nil))

	if recorder.Code != http.StatusBadRequest || !strings.Contains(recorder.Body.String(), message) {
		t.Fatalf("original response changed: status=%d bytes=%d", recorder.Code, recorder.Body.Len())
	}
	var status int
	var method, path, storedMessage, requestID, userID string
	if err := database.SQL.QueryRow(`
		SELECT status_code, method, path, message, request_id, user_id
		FROM error_events
	`).Scan(&status, &method, &path, &storedMessage, &requestID, &userID); err != nil {
		t.Fatal(err)
	}
	if status != 400 || method != http.MethodPost || path != "/api/v1/sync" ||
		requestID == "" || userID != "user-1" || len(storedMessage) > maxRecordedErrorBody {
		t.Fatalf("bad stored error: status=%d method=%q path=%q request=%q user=%q message_bytes=%d",
			status, method, path, requestID, userID, len(storedMessage))
	}
}

func TestErrorRecorderIgnoresSuccessfulAndStaticResponses(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "errors.db"), logger)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	handler := ErrorRecorder(database, logger)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/ok" {
			w.WriteHeader(http.StatusOK)
			return
		}
		http.NotFound(w, r)
	}))
	handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/api/v1/ok", nil))
	handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/missing.js", nil))

	var count int
	if err := database.SQL.QueryRow("SELECT COUNT(*) FROM error_events").Scan(&count); err != nil || count != 0 {
		t.Fatalf("unexpected recorded errors: count=%d err=%v", count, err)
	}
}

package middleware

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
)

const maxRecordedErrorBody = 4096

type errorRequestContextKey string

const errorUserKey errorRequestContextKey = "error_user"

type observedErrorUser struct {
	ID string
}

// ObserveErrorUser attaches authenticated identity to the outer error recorder.
// The pointer is intentionally mutable: authentication runs inside the recorder
// and therefore cannot replace the request context visible to outer middleware.
func ObserveErrorUser(ctx context.Context, userID string) {
	if observed, ok := ctx.Value(errorUserKey).(*observedErrorUser); ok {
		observed.ID = userID
	}
}

type errorResponseWriter struct {
	http.ResponseWriter
	statusCode int
	body       bytes.Buffer
}

func (w *errorResponseWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *errorResponseWriter) WriteHeader(statusCode int) {
	if w.statusCode == 0 {
		w.statusCode = statusCode
	}
	w.ResponseWriter.WriteHeader(statusCode)
}

func (w *errorResponseWriter) Write(payload []byte) (int, error) {
	if w.statusCode == 0 {
		w.statusCode = http.StatusOK
	}
	if w.statusCode >= http.StatusBadRequest && w.body.Len() < maxRecordedErrorBody {
		remaining := maxRecordedErrorBody - w.body.Len()
		captured := payload
		if len(captured) > remaining {
			captured = captured[:remaining]
		}
		_, _ = w.body.Write(captured)
	}
	return w.ResponseWriter.Write(payload)
}

func errorMessage(body []byte) string {
	var envelope struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &envelope) == nil && strings.TrimSpace(envelope.Error) != "" {
		return strings.TrimSpace(envelope.Error)
	}
	return strings.TrimSpace(string(body))
}

// ErrorRecorder persists failed API responses after the response has been sent.
// Recording failures are logged but never alter the original response.
func ErrorRecorder(database *db.DB, logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			observed := &observedErrorUser{}
			ctx := context.WithValue(r.Context(), errorUserKey, observed)
			r = r.WithContext(ctx)
			recorder := &errorResponseWriter{ResponseWriter: w}

			next.ServeHTTP(recorder, r)
			if recorder.statusCode < http.StatusBadRequest || !strings.HasPrefix(r.URL.Path, "/api/") {
				return
			}

			_, err := database.SQL.ExecContext(context.WithoutCancel(r.Context()), `
				INSERT INTO error_events (
					occurred_at, status_code, method, path, message, request_id, user_id, source
				) VALUES (?, ?, ?, ?, ?, ?, ?, 'http')
			`, time.Now().UnixMilli(), recorder.statusCode, r.Method, r.URL.Path,
				errorMessage(recorder.body.Bytes()), GetRequestID(r.Context()), observed.ID)
			if err != nil {
				logger.Error("failed to persist API error",
					"request_id", GetRequestID(r.Context()),
					"status", recorder.statusCode,
					"error", err,
				)
			}
		})
	}
}

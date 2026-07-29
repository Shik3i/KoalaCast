package middleware

import (
	"log/slog"
	"net/http"
	"time"
)

type responseWriterInterceptor struct {
	http.ResponseWriter
	statusCode int
	bytesCount int
}

func (rwi *responseWriterInterceptor) Unwrap() http.ResponseWriter {
	return rwi.ResponseWriter
}

func (rwi *responseWriterInterceptor) WriteHeader(statusCode int) {
	rwi.statusCode = statusCode
	rwi.ResponseWriter.WriteHeader(statusCode)
}

func (rwi *responseWriterInterceptor) Write(b []byte) (int, error) {
	if rwi.statusCode == 0 {
		rwi.statusCode = http.StatusOK
	}
	n, err := rwi.ResponseWriter.Write(b)
	rwi.bytesCount += n
	return n, err
}

func Logger(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			reqID := GetRequestID(r.Context())

			rwi := &responseWriterInterceptor{ResponseWriter: w}
			next.ServeHTTP(rwi, r)

			duration := time.Since(start)

			logger.Info("http request",
				"request_id", reqID,
				"method", r.Method,
				"path", r.URL.Path,
				"status", rwi.statusCode,
				"duration_ms", duration.Milliseconds(),
				"bytes", rwi.bytesCount,
			)
		})
	}
}

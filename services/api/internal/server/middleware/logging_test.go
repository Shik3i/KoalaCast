package middleware

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRequestLoggerKeepsUntrustedValuesInOneJSONRecord(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, nil))
	hostile := "user\r\n{\"level\":\"ERROR\"}\t\x1b[31m"
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.URL.Path = "/" + hostile
	req.Method = hostile
	req = req.WithContext(context.WithValue(req.Context(), RequestIDKey, hostile))
	Logger(logger)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) })).ServeHTTP(httptest.NewRecorder(), req)
	if strings.Count(output.String(), "\n") != 1 {
		t.Fatalf("log record injection: %q", output.String())
	}
	var record map[string]any
	if err := json.Unmarshal(output.Bytes(), &record); err != nil {
		t.Fatal(err)
	}
	if record["request_id"] != hostile || record["path"] != "/"+hostile || record["method"] != hostile || record["level"] != "INFO" {
		t.Fatalf("unexpected log record: %#v", record)
	}
}

func TestJSONLoggerKeepsPodcastIDsAndErrorsInOneRecord(t *testing.T) {
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, nil))
	hostile := "id\r\n{\"msg\":\"forged record\"}\x1b[31m"
	logger.Warn("web push send failed", "podcast_id", hostile, "error", errors.New(hostile))
	if strings.Count(output.String(), "\n") != 1 {
		t.Fatalf("log injection: %q", output.String())
	}
	var record map[string]any
	if err := json.Unmarshal(output.Bytes(), &record); err != nil {
		t.Fatal(err)
	}
	if record["podcast_id"] != hostile || record["error"] != hostile || record["msg"] != "web push send failed" {
		t.Fatalf("unexpected record: %#v", record)
	}
}

package handlers

import (
	"bytes"
	"encoding/json"
	"io/ioutil"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
)

func TestOPMLHandler_Import_InvalidXML(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_opml_test_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	dbPath := filepath.Join(tempDir, "test.db")
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))

	database, err := db.OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	handler := &OPMLHandler{
		DB:           database,
		MaxResponseB: 10485760,
	}

	invalidXML := []byte(`<opml><unclosed_tag>`)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/opml/import", bytes.NewBuffer(invalidXML))
	rec := httptest.NewRecorder()

	handler.Import(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("expected status 400 for invalid OPML XML, got %d", rec.Code)
	}
}

func TestOPMLHandler_Export_Unauthenticated(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_opml_exp_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	dbPath := filepath.Join(tempDir, "test.db")
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))

	database, err := db.OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	handler := &OPMLHandler{
		DB:           database,
		MaxResponseB: 10485760,
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/opml/export", nil)
	rec := httptest.NewRecorder()

	handler.Export(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected status 401 for unauthenticated OPML export, got %d", rec.Code)
	}
}

func TestOPMLHandler_Import_SSRF_Protection(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_opml_ssrf_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	dbPath := filepath.Join(tempDir, "test.db")
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))

	database, err := db.OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	handler := &OPMLHandler{
		DB:           database,
		MaxResponseB: 10485760,
	}

	// OPML containing a loopback address (127.0.0.1) -> Must be rejected during import
	opmlWithLoopback := []byte(`<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0">
	<body>
		<outline type="rss" text="Private Feed" xmlUrl="http://127.0.0.1/private_rss.xml"/>
	</body>
</opml>`)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/opml/import", bytes.NewBuffer(opmlWithLoopback))
	rec := httptest.NewRecorder()

	handler.Import(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 OK (with partial failure report), got %d", rec.Code)
	}

	var report OPMLImportReport
	_ = json.NewDecoder(rec.Body).Decode(&report)

	if report.TotalFound != 1 || report.Skipped != 1 || report.Imported != 0 {
		t.Errorf("expected 1 total, 1 skipped, 0 imported for SSRF loopback feed in OPML, got: %+v", report)
	}
}

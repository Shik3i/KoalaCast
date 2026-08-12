package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"io/ioutil"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
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
		DB: database,
	}
	if _, err := database.SQL.Exec(`
		INSERT INTO users
			(id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at)
		VALUES ('u-opml', 'OPML User', 'opml-user', 'hash', 'recovery', 0, 0)
	`); err != nil {
		t.Fatalf("seed user: %v", err)
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
		DB: database,
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
		DB: database,
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

func TestOPMLHandler_Import_UsesCompleteFeedIngestionAndReturnsResolvedMetadata(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_opml_ingest_*")
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

	const sourceURL = "https://example.org/original.xml"
	const canonicalURL = "https://cdn.example.org/canonical.xml"
	now := time.Now().UnixMilli()
	if _, err := database.SQL.Exec(`
		INSERT INTO users
			(id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at)
		VALUES ('u-opml', 'opml-user', 'opml-user', 'hash', 'recovery', ?, ?)
	`, now, now); err != nil {
		t.Fatalf("insert auth user: %v", err)
	}
	called := false
	handler := &OPMLHandler{
		DB: database,
		IngestFeed: func(ctx context.Context, feedURL string) (string, error) {
			called = true
			if feedURL != sourceURL {
				t.Fatalf("expected source feed URL %q, got %q", sourceURL, feedURL)
			}
			now := time.Now().UnixMilli()
			_, err := database.SQL.ExecContext(ctx, `
				INSERT INTO podcasts (id, feed_url, title, artwork_url, created_at, updated_at)
				VALUES ('resolved-id', ?, 'Resolved podcast', 'https://cdn.example.org/cover.jpg', ?, ?)
			`, canonicalURL, now, now)
			return "resolved-id", err
		},
	}

	payload := []byte(`<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0"><body>
	<outline type="rss" text="Podcast" xmlUrl="https://example.org/original.xml"/>
</body></opml>`)
	authCtx := context.WithValue(
		context.Background(),
		customMiddleware.UserContextKey,
		&customMiddleware.AuthUser{ID: "u-opml"},
	)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/opml/import", bytes.NewBuffer(payload)).
		WithContext(authCtx)
	rec := httptest.NewRecorder()

	handler.Import(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d: %s", rec.Code, rec.Body.String())
	}
	if !called {
		t.Fatal("expected configured full feed ingester to be called")
	}
	var report OPMLImportReport
	if err := json.NewDecoder(rec.Body).Decode(&report); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if report.Imported != 1 || report.Skipped != 0 || len(report.Podcasts) != 1 {
		t.Fatalf("unexpected report: %+v", report)
	}
	got := report.Podcasts[0]
	if got.ID != "resolved-id" || got.SourceURL != sourceURL || got.FeedURL != canonicalURL ||
		got.ArtworkURL != "https://cdn.example.org/cover.jpg" {
		t.Fatalf("unexpected resolved podcast: %+v", got)
	}
	var syncEntries int
	if err := database.SQL.QueryRow(`
		SELECT COUNT(*) FROM sync_log
		WHERE user_id='u-opml' AND entity_type='subscription' AND entity_id='resolved-id'
	`).Scan(&syncEntries); err != nil || syncEntries != 1 {
		t.Fatalf("import did not emit subscription sync entry: count=%d err=%v", syncEntries, err)
	}
}

func TestOPMLHandler_ImportStartedBeforeDataResetCannotRestoreSubscriptions(t *testing.T) {
	tempDir := t.TempDir()
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(tempDir, "test.db"), logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	now := time.Now().UnixMilli()
	if _, err := database.SQL.Exec(`
		INSERT INTO users
			(id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at)
		VALUES ('u-opml-reset', 'opml-user', 'opml-user', 'hash', 'recovery', ?, ?)
	`, now, now); err != nil {
		t.Fatalf("insert auth user: %v", err)
	}

	ingestionStarted := make(chan struct{})
	continueIngestion := make(chan struct{})
	handler := &OPMLHandler{
		DB: database,
		IngestFeed: func(ctx context.Context, feedURL string) (string, error) {
			close(ingestionStarted)
			<-continueIngestion
			_, err := database.SQL.ExecContext(ctx, `
				INSERT INTO podcasts (id, feed_url, title, created_at, updated_at)
				VALUES ('reset-race-podcast', ?, 'Reset race', ?, ?)
			`, feedURL, now, now)
			return "reset-race-podcast", err
		},
	}

	payload := []byte(`<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0"><body>
	<outline type="rss" text="Podcast" xmlUrl="https://example.org/reset-race.xml"/>
</body></opml>`)
	authCtx := context.WithValue(
		context.Background(),
		customMiddleware.UserContextKey,
		&customMiddleware.AuthUser{ID: "u-opml-reset"},
	)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/opml/import", bytes.NewBuffer(payload)).
		WithContext(authCtx)
	rec := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		defer close(done)
		handler.Import(rec, req)
	}()

	<-ingestionStarted
	if _, err := database.SQL.Exec(`
		UPDATE users SET data_generation = data_generation + 1 WHERE id='u-opml-reset';
		DELETE FROM subscriptions WHERE user_id='u-opml-reset';
	`); err != nil {
		t.Fatalf("reset account data: %v", err)
	}
	close(continueIngestion)
	<-done

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var report OPMLImportReport
	if err := json.NewDecoder(rec.Body).Decode(&report); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if report.Imported != 0 || report.Skipped != 1 {
		t.Fatalf("stale import was not rejected: %+v", report)
	}
	var subscriptions int
	if err := database.SQL.QueryRow(
		"SELECT COUNT(*) FROM subscriptions WHERE user_id='u-opml-reset'",
	).Scan(&subscriptions); err != nil {
		t.Fatal(err)
	}
	if subscriptions != 0 {
		t.Fatalf("stale OPML import restored %d subscription(s)", subscriptions)
	}
}

func TestUniqueFeedURLs_PreservesOrderAndRemovesDuplicates(t *testing.T) {
	got := uniqueFeedURLs([]string{
		" https://example.org/first.xml ",
		"https://example.org/second.xml",
		"https://example.org/first.xml",
		"",
	})
	want := []string{"https://example.org/first.xml", "https://example.org/second.xml"}
	if len(got) != len(want) {
		t.Fatalf("expected %v, got %v", want, got)
	}
	for index := range want {
		if got[index] != want[index] {
			t.Fatalf("expected %v, got %v", want, got)
		}
	}
}

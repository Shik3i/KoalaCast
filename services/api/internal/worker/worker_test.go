package worker

import (
	"context"
	"io/ioutil"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

func TestFeedWorker_RefreshSingleFeed_Success(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_worker_test_*")
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

	// Mock RSS HTTP Server
	sampleRSS := `<?xml version="1.0" encoding="UTF-8"?>
	<rss version="2.0">
		<channel>
			<title>Worker Test Podcast</title>
			<description>Test Podcast Description</description>
			<item>
				<guid>ep-1</guid>
				<title>Episode 1</title>
				<enclosure url="http://example.com/ep1.mp3" type="audio/mpeg" length="123456"/>
			</item>
		</channel>
	</rss>`

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/rss+xml")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(sampleRSS))
	}))
	defer ts.Close()

	cfg := &config.Config{
		FeedWorkerConcurrency: 2,
		FeedRequestTimeoutMS:  5000,
		FeedMaxResponseBytes:  10485760,
	}

	worker := NewFeedWorker(database, cfg, logger)
	worker.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{
		AllowLoopback: true,
	})
	ctx := context.Background()

	// Seed podcast record
	podcastID := "pod-worker-1"
	nowMs := time.Now().UnixMilli()
	_, err = database.SQL.ExecContext(ctx, `
		INSERT INTO podcasts (id, feed_url, title, created_at, updated_at)
		VALUES (?, ?, 'Old Title', ?, ?)
	`, podcastID, ts.URL, nowMs, nowMs)
	if err != nil {
		t.Fatalf("failed to insert podcast: %v", err)
	}

	err = worker.RefreshSingleFeed(ctx, podcastID, ts.URL, "", "")
	if err != nil {
		t.Fatalf("RefreshSingleFeed failed: %v", err)
	}

	// Verify podcast title was updated
	var title string
	_ = database.SQL.QueryRowContext(ctx, "SELECT title FROM podcasts WHERE id = ?", podcastID).Scan(&title)
	if title != "Worker Test Podcast" {
		t.Errorf("expected updated title 'Worker Test Podcast', got '%s'", title)
	}

	// Verify episode was inserted
	var count int
	_ = database.SQL.QueryRowContext(ctx, "SELECT COUNT(*) FROM episodes WHERE podcast_id = ?", podcastID).Scan(&count)
	if count != 1 {
		t.Errorf("expected 1 episode inserted, got %d", count)
	}
}

func TestFeedWorker_RefreshSingleFeed_304NotModified(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_worker_304_*")
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

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("If-None-Match") == "etag-123" {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer ts.Close()

	cfg := &config.Config{
		FeedWorkerConcurrency: 2,
		FeedRequestTimeoutMS:  5000,
		FeedMaxResponseBytes:  10485760,
	}

	worker := NewFeedWorker(database, cfg, logger)
	worker.httpClient = rss.NewSafeHTTPClient(rss.SafeTransportConfig{
		AllowLoopback: true,
	})
	ctx := context.Background()

	podcastID := "pod-worker-304"
	nowMs := time.Now().UnixMilli()
	_, _ = database.SQL.ExecContext(ctx, `
		INSERT INTO podcasts (id, feed_url, title, etag, created_at, updated_at)
		VALUES (?, ?, 'Title', 'etag-123', ?, ?)
	`, podcastID, ts.URL, nowMs, nowMs)

	err = worker.RefreshSingleFeed(ctx, podcastID, ts.URL, "etag-123", "")
	if err != nil {
		t.Fatalf("expected clean handling of 304 Not Modified, got error: %v", err)
	}

	var errorCount int
	_ = database.SQL.QueryRowContext(ctx, "SELECT consecutive_error_count FROM podcasts WHERE id = ?", podcastID).Scan(&errorCount)
	if errorCount != 0 {
		t.Errorf("expected consecutive_error_count to be 0 for 304 Not Modified, got %d", errorCount)
	}
}

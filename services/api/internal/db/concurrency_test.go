package db

import (
	"context"
	"fmt"
	"io/ioutil"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// TestConcurrentWrites verifies the pool + _txlock=immediate + busy_timeout
// configuration survives many parallel writers without "database is locked".
func TestConcurrentWrites(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_conc_*")
	if err != nil {
		t.Fatalf("temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := OpenDB(filepath.Join(tempDir, "conc.db"), logger)
	if err != nil {
		t.Fatalf("OpenDB: %v", err)
	}
	defer database.Close()

	ctx := context.Background()
	now := time.Now().UnixMilli()

	const workers = 16
	const perWorker = 25
	var wg sync.WaitGroup
	errCh := make(chan error, workers*perWorker)

	for wkr := 0; wkr < workers; wkr++ {
		wg.Add(1)
		go func(wkr int) {
			defer wg.Done()
			for i := 0; i < perWorker; i++ {
				id := fmt.Sprintf("pod-%d-%d", wkr, i)
				url := fmt.Sprintf("https://example.com/%d/%d.xml", wkr, i)
				// Mix of transactional and non-transactional writes.
				if i%2 == 0 {
					tx, err := database.SQL.BeginTx(ctx, nil)
					if err != nil {
						errCh <- err
						return
					}
					_, err = tx.ExecContext(ctx, `INSERT INTO podcasts (id, feed_url, title, created_at, updated_at) VALUES (?, ?, 'T', ?, ?)`, id, url, now, now)
					if err != nil {
						_ = tx.Rollback()
						errCh <- err
						return
					}
					if err := tx.Commit(); err != nil {
						errCh <- err
						return
					}
				} else {
					_, err := database.SQL.ExecContext(ctx, `INSERT INTO podcasts (id, feed_url, title, created_at, updated_at) VALUES (?, ?, 'T', ?, ?)`, id, url, now, now)
					if err != nil {
						errCh <- err
						return
					}
				}
			}
		}(wkr)
	}

	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Fatalf("concurrent write failed: %v", err)
	}

	var count int
	if err := database.SQL.QueryRowContext(ctx, "SELECT COUNT(*) FROM podcasts").Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if want := workers * perWorker; count != want {
		t.Errorf("expected %d rows, got %d", want, count)
	}
}

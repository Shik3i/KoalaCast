package worker

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	"github.com/google/uuid"
)

type FeedWorker struct {
	db         *db.DB
	cfg        *config.Config
	logger     *slog.Logger
	httpClient *http.Client
	stopChan   chan struct{}
	wg         sync.WaitGroup
	mu         sync.Mutex

	// Metrics
	LastRunAt       time.Time
	SuccessCount    int64
	FailureCount    int64
	IsWorkerRunning bool
}

func NewFeedWorker(database *db.DB, cfg *config.Config, logger *slog.Logger) *FeedWorker {
	return &FeedWorker{
		db:       database,
		cfg:      cfg,
		logger:   logger,
		stopChan: make(chan struct{}),
		httpClient: rss.NewSafeHTTPClient(rss.SafeTransportConfig{
			ConnectTimeout:  time.Duration(cfg.FeedRequestTimeoutMS) * time.Millisecond,
			ResponseTimeout: time.Duration(cfg.FeedRequestTimeoutMS) * time.Millisecond,
		}),
	}
}

func (w *FeedWorker) Start(ctx context.Context) {
	w.mu.Lock()
	w.IsWorkerRunning = true
	w.mu.Unlock()

	w.wg.Add(1)
	go w.runLoop(ctx)
}

func (w *FeedWorker) Stop() {
	close(w.stopChan)
	w.wg.Wait()

	w.mu.Lock()
	w.IsWorkerRunning = false
	w.mu.Unlock()
}

func (w *FeedWorker) runLoop(ctx context.Context) {
	defer w.wg.Done()

	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	// Run initial refresh check
	w.RefreshScheduledFeeds(ctx)

	for {
		select {
		case <-ctx.Done():
			return
		case <-w.stopChan:
			return
		case <-ticker.C:
			w.RefreshScheduledFeeds(ctx)
		}
	}
}

func (w *FeedWorker) RefreshScheduledFeeds(ctx context.Context) {
	w.mu.Lock()
	w.LastRunAt = time.Now().UTC()
	w.mu.Unlock()

	nowMs := time.Now().UnixMilli()

	// Query podcasts due for refresh
	rows, err := w.db.SQL.QueryContext(ctx, `
		SELECT id, feed_url, etag, last_modified, consecutive_error_count
		FROM podcasts
		WHERE next_scheduled_fetch_at <= ? OR last_successful_fetch_at = 0
		LIMIT 50
	`, nowMs)
	if err != nil {
		w.logger.Error("failed to query feeds for refresh", "error", err)
		return
	}
	defer rows.Close()

	type podcastItem struct {
		id            string
		feedURL       string
		etag          string
		lastModified  string
		errorCount    int
	}

	var toFetch []podcastItem
	for rows.Next() {
		var item podcastItem
		if err := rows.Scan(&item.id, &item.feedURL, &item.etag, &item.lastModified, &item.errorCount); err == nil {
			toFetch = append(toFetch, item)
		}
	}

	if len(toFetch) == 0 {
		return
	}

	w.logger.Info("starting feed refresh batch", "count", len(toFetch))

	// Worker pool concurrency control
	concurrency := w.cfg.FeedWorkerConcurrency
	if concurrency <= 0 {
		concurrency = 5
	}

	sem := make(chan struct{}, concurrency)
	var wg sync.WaitGroup

	for _, item := range toFetch {
		wg.Add(1)
		sem <- struct{}{}

		go func(pod podcastItem) {
			defer wg.Done()
			defer func() { <-sem }()

			if err := w.RefreshSingleFeed(ctx, pod.id, pod.feedURL, pod.etag, pod.lastModified); err != nil {
				w.logger.Warn("feed refresh failed", "podcast_id", pod.id, "feed_url", pod.feedURL, "error", err)
				w.mu.Lock()
				w.FailureCount++
				w.mu.Unlock()
			} else {
				w.mu.Lock()
				w.SuccessCount++
				w.mu.Unlock()
			}
		}(item)
	}

	wg.Wait()
}

func (w *FeedWorker) RefreshSingleFeed(ctx context.Context, podcastID, feedURL, etag, lastModified string) error {
	nowMs := time.Now().UnixMilli()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, feedURL, nil)
	if err != nil {
		w.updateFeedError(podcastID, "INVALID_URL", 0, err.Error())
		return fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("User-Agent", "KoalaCast/1.0 (+https://github.com/Shik3i/KoalaCast)")
	req.Header.Set("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")

	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}
	if lastModified != "" {
		req.Header.Set("If-Modified-Since", lastModified)
	}

	resp, err := w.httpClient.Do(req)
	if err != nil {
		w.updateFeedError(podcastID, "NETWORK_ERROR", 0, err.Error())
		return fmt.Errorf("http request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotModified {
		// 304 Not Modified -> Schedule next fetch with exponential backoff
		_, _ = w.db.SQL.ExecContext(ctx, `
			UPDATE podcasts
			SET last_fetch_attempt_at = ?,
				next_scheduled_fetch_at = ?,
				consecutive_error_count = 0,
				last_error_category = ''
			WHERE id = ?
		`, nowMs, nowMs+86400000, podcastID)
		return nil
	}

	if resp.StatusCode != http.StatusOK {
		w.updateFeedError(podcastID, "HTTP_ERROR", resp.StatusCode, fmt.Sprintf("HTTP %d", resp.StatusCode))
		return fmt.Errorf("unexpected HTTP status: %d", resp.StatusCode)
	}

	// Read response body with byte limit
	limitReader := rss.LimitResponseBody(resp.Body, w.cfg.FeedMaxResponseBytes)
	parsedFeed, err := rss.ParseFeedXML(limitReader)
	if err != nil {
		w.updateFeedError(podcastID, "PARSE_ERROR", resp.StatusCode, err.Error())
		return fmt.Errorf("failed to parse feed XML: %w", err)
	}

	newETag := resp.Header.Get("ETag")
	newLastModified := resp.Header.Get("Last-Modified")

	// Update Podcast metadata
	tx, err := w.db.SQL.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	_, err = tx.ExecContext(ctx, `
		UPDATE podcasts
		SET title = ?,
			description = ?,
			author = ?,
			artwork_url = ?,
			link = ?,
			language = ?,
			copyright = ?,
			last_fetch_attempt_at = ?,
			last_successful_fetch_at = ?,
			next_scheduled_fetch_at = ?,
			etag = ?,
			last_modified = ?,
			consecutive_error_count = 0,
			last_error_category = '',
			last_http_status = 200,
			updated_at = ?
		WHERE id = ?
	`, parsedFeed.Title, parsedFeed.Description, parsedFeed.Author, parsedFeed.ArtworkURL,
		parsedFeed.Link, parsedFeed.Language, parsedFeed.Copyright,
		nowMs, nowMs, nowMs+86400000, newETag, newLastModified, nowMs, podcastID)
	if err != nil {
		return fmt.Errorf("failed to update podcast: %w", err)
	}

	// Insert or Update Episodes using StableIdentityKey
	for _, ep := range parsedFeed.Episodes {
		var pubDateUnix int64
		if ep.HasPubDate {
			pubDateUnix = ep.PubDate.Unix()
		}

		hasPubDateInt := 0
		if ep.HasPubDate {
			hasPubDateInt = 1
		}

		explicitInt := 0
		if ep.Explicit {
			explicitInt = 1
		}

		var existingID string
		err := tx.QueryRowContext(ctx, `
			SELECT id FROM episodes WHERE podcast_id = ? AND stable_identity_key = ?
		`, podcastID, ep.StableKey).Scan(&existingID)

		if err == sql.ErrNoRows {
			episodeID := uuid.New().String()
			_, err = tx.ExecContext(ctx, `
				INSERT INTO episodes (
					id, podcast_id, stable_identity_key, guid, fallback_hash, title, description,
					content_encoded, pub_date, has_pub_date, duration_ms, enclosure_url,
					enclosure_type, enclosure_length, artwork_url, episode_number, season_number,
					episode_type, explicit, link, created_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			`, episodeID, podcastID, ep.StableKey, ep.GUID, ep.FallbackHash, ep.Title, ep.Description,
				ep.ContentEncoded, pubDateUnix, hasPubDateInt, ep.DurationMS, ep.EnclosureURL,
				ep.EnclosureType, ep.EnclosureLength, ep.ArtworkURL, ep.EpisodeNumber, ep.SeasonNumber,
				ep.EpisodeType, explicitInt, ep.Link, nowMs)
			if err != nil {
				return fmt.Errorf("failed to insert episode: %w", err)
			}
		} else if err == nil {
			_, err = tx.ExecContext(ctx, `
				UPDATE episodes
				SET title = ?,
					description = ?,
					content_encoded = ?,
					duration_ms = ?,
					enclosure_url = ?,
					enclosure_type = ?,
					enclosure_length = ?,
					artwork_url = ?,
					link = ?
				WHERE id = ?
			`, ep.Title, ep.Description, ep.ContentEncoded, ep.DurationMS, ep.EnclosureURL,
				ep.EnclosureType, ep.EnclosureLength, ep.ArtworkURL, ep.Link, existingID)
			if err != nil {
				return fmt.Errorf("failed to update episode: %w", err)
			}
		}
	}

	return tx.Commit()
}

func (w *FeedWorker) updateFeedError(podcastID, category string, httpStatus int, errorMsg string) {
	nowMs := time.Now().UnixMilli()
	// Exponential backoff multiplier
	_, _ = w.db.SQL.Exec(`
		UPDATE podcasts
		SET last_fetch_attempt_at = ?,
			consecutive_error_count = consecutive_error_count + 1,
			last_error_category = ?,
			last_http_status = ?,
			next_scheduled_fetch_at = ? + (consecutive_error_count + 1) * 3600000
		WHERE id = ?
	`, nowMs, category, httpStatus, nowMs, podcastID)
}

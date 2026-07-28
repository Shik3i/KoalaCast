package handlers

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
)

type OPMLHandler struct {
	DB         *db.DB
	IngestFeed func(context.Context, string) (string, error)
}

type opmlDocument struct {
	XMLName xml.Name `xml:"opml"`
	Version string   `xml:"version,attr"`
	Body    opmlBody `xml:"body"`
}

type opmlBody struct {
	Outlines []opmlOutline `xml:"outline"`
}

type opmlOutline struct {
	Text     string        `xml:"text,attr"`
	Title    string        `xml:"title,attr"`
	XmlUrl   string        `xml:"xmlUrl,attr"`
	XmlUrl2  string        `xml:"xmlurl,attr"`
	XmlUrl3  string        `xml:"XMLURL,attr"`
	URL      string        `xml:"url,attr"`
	Outlines []opmlOutline `xml:"outline"`
}

type OPMLImportReport struct {
	TotalFound int               `json:"total_found"`
	Imported   int               `json:"imported"`
	Skipped    int               `json:"skipped"`
	Failures   []OPMLImportError `json:"failures"`
	// Podcasts carries the resolved records for every successfully imported feed so
	// a local-first (anonymous) client can add them to its on-device subscription
	// store — the server only persists subscriptions for signed-in accounts.
	Podcasts []OPMLImportedPodcast `json:"podcasts"`
}

type OPMLImportError struct {
	URL    string `json:"url"`
	Reason string `json:"reason"`
}

type OPMLImportedPodcast struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	SourceURL  string `json:"source_url"`
	FeedURL    string `json:"feed_url"`
	ArtworkURL string `json:"artwork_url"`
}

func (h *OPMLHandler) Import(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())

	// Read OPML XML payload
	body, err := io.ReadAll(io.LimitReader(r.Body, 5*1024*1024))
	if err != nil || len(body) == 0 {
		http.Error(w, `{"error":"invalid or empty OPML file payload"}`, http.StatusBadRequest)
		return
	}

	var doc opmlDocument
	if err := xml.Unmarshal(body, &doc); err != nil {
		http.Error(w, `{"error":"failed to parse OPML XML document"}`, http.StatusBadRequest)
		return
	}

	feedURLs := uniqueFeedURLs(extractOutlines(doc.Body.Outlines))

	// Importing fetches each feed server-side and can legitimately take much longer
	// than the server's default 15s WriteTimeout. Extend the write deadline for this
	// one response so a multi-feed import isn't cut off mid-flight. Best-effort:
	// harmless if the platform doesn't support deadline control.
	if rc := http.NewResponseController(w); rc != nil {
		_ = rc.SetWriteDeadline(time.Now().Add(4 * time.Minute))
	}

	report := OPMLImportReport{
		TotalFound: len(feedURLs),
		Failures:   make([]OPMLImportError, 0),
		Podcasts:   make([]OPMLImportedPodcast, 0),
	}

	// Bound the fan-out: each URL triggers a server-side fetch plus DB writes.
	const maxImportFeeds = 500
	if len(feedURLs) > maxImportFeeds {
		report.Failures = append(report.Failures, OPMLImportError{
			URL:    "",
			Reason: fmt.Sprintf("OPML contains %d feeds; only the first %d were processed", len(feedURLs), maxImportFeeds),
		})
		report.Skipped += len(feedURLs) - maxImportFeeds
		feedURLs = feedURLs[:maxImportFeeds]
	}

	type result struct {
		podcast OPMLImportedPodcast
		err     error
	}
	results := make([]result, len(feedURLs))
	semaphore := make(chan struct{}, 4)
	var wait sync.WaitGroup

	for index, rawURL := range feedURLs {
		index, feedURL := index, strings.TrimSpace(rawURL)
		wait.Add(1)
		go func() {
			defer wait.Done()
			semaphore <- struct{}{}
			defer func() { <-semaphore }()

			if err := rss.ValidateURL(feedURL); err != nil {
				results[index].err = fmt.Errorf("SSRF validation failed: %w", err)
				return
			}
			results[index].podcast.SourceURL = feedURL
			if h.IngestFeed == nil {
				results[index].err = fmt.Errorf("feed ingestion unavailable")
				return
			}
			podcastID, err := h.IngestFeed(r.Context(), feedURL)
			if err != nil {
				results[index].err = err
				return
			}
			results[index].err = h.DB.SQL.QueryRowContext(
				r.Context(),
				"SELECT id, title, feed_url, artwork_url FROM podcasts WHERE id = ?",
				podcastID,
			).Scan(
				&results[index].podcast.ID,
				&results[index].podcast.Title,
				&results[index].podcast.FeedURL,
				&results[index].podcast.ArtworkURL,
			)
		}()
	}
	wait.Wait()

	for index, item := range results {
		feedURL := strings.TrimSpace(feedURLs[index])
		if item.err != nil {
			report.Failures = append(report.Failures, OPMLImportError{URL: feedURL, Reason: item.err.Error()})
			report.Skipped++
			continue
		}
		if authUser != nil {
			nowMs := time.Now().UnixMilli()
			if _, err := h.DB.SQL.ExecContext(r.Context(), `
				INSERT INTO subscriptions (user_id, podcast_id, created_at, updated_at, is_deleted, sync_version)
				VALUES (?, ?, ?, ?, 0, 1)
				ON CONFLICT(user_id, podcast_id) DO UPDATE SET is_deleted = 0, updated_at = excluded.updated_at
			`, authUser.ID, item.podcast.ID, nowMs, nowMs); err != nil {
				report.Failures = append(report.Failures, OPMLImportError{
					URL:    feedURL,
					Reason: "failed to persist subscription",
				})
				report.Skipped++
				continue
			}
		}
		report.Imported++
		report.Podcasts = append(report.Podcasts, item.podcast)
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(report)
}

func extractOutlines(outlines []opmlOutline) []string {
	var urls []string
	for _, o := range outlines {
		u := strings.TrimSpace(o.XmlUrl)
		if u == "" {
			u = strings.TrimSpace(o.XmlUrl2)
		}
		if u == "" {
			u = strings.TrimSpace(o.XmlUrl3)
		}
		if u == "" {
			u = strings.TrimSpace(o.URL)
		}
		if u != "" {
			urls = append(urls, u)
		}
		if len(o.Outlines) > 0 {
			urls = append(urls, extractOutlines(o.Outlines)...)
		}
	}
	return urls
}

func uniqueFeedURLs(urls []string) []string {
	seen := make(map[string]struct{}, len(urls))
	unique := make([]string, 0, len(urls))
	for _, rawURL := range urls {
		feedURL := strings.TrimSpace(rawURL)
		if feedURL == "" {
			continue
		}
		if _, exists := seen[feedURL]; exists {
			continue
		}
		seen[feedURL] = struct{}{}
		unique = append(unique, feedURL)
	}
	return unique
}

func (h *OPMLHandler) Export(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	rows, err := h.DB.SQL.QueryContext(r.Context(), `
		SELECT p.title, p.feed_url, p.link
		FROM subscriptions s
		JOIN podcasts p ON s.podcast_id = p.id
		WHERE s.user_id = ? AND s.is_deleted = 0
	`, authUser.ID)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	outlines := make([]opmlOutline, 0)
	for rows.Next() {
		var title, feedURL, link string
		if err := rows.Scan(&title, &feedURL, &link); err == nil {
			outlines = append(outlines, opmlOutline{
				Text:   title,
				Title:  title,
				XmlUrl: feedURL,
			})
		}
	}

	doc := opmlDocument{
		Version: "2.0",
		Body: opmlBody{
			Outlines: outlines,
		},
	}

	w.Header().Set("Content-Type", "application/xml")
	w.Header().Set("Content-Disposition", "attachment; filename=\"koalacast_subscriptions.opml\"")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(xml.Header))
	_ = xml.NewEncoder(w).Encode(doc)
}

package handlers

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
	"github.com/google/uuid"
)

type OPMLHandler struct {
	DB           *db.DB
	MaxResponseB int64
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

	feedURLs := extractOutlines(doc.Body.Outlines)

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

	// Bound the fan-out: each URL triggers a server-side fetch (up to
	// MaxResponseB bytes) plus DB writes, all sequentially within one request.
	// Without a cap a single OPML upload could pin the server on outbound I/O for
	// minutes and blow the server write timeout.
	const maxImportFeeds = 500
	if len(feedURLs) > maxImportFeeds {
		report.Failures = append(report.Failures, OPMLImportError{
			URL:    "",
			Reason: fmt.Sprintf("OPML contains %d feeds; only the first %d were processed", len(feedURLs), maxImportFeeds),
		})
		report.Skipped += len(feedURLs) - maxImportFeeds
		feedURLs = feedURLs[:maxImportFeeds]
	}

	client := rss.NewSafeHTTPClient(rss.SafeTransportConfig{ConnectTimeout: 10 * time.Second})

	for _, rawURL := range feedURLs {
		feedURL := strings.TrimSpace(rawURL)

		// 1. SSRF Check
		if err := rss.ValidateURL(feedURL); err != nil {
			report.Failures = append(report.Failures, OPMLImportError{URL: feedURL, Reason: "SSRF validation failed: " + err.Error()})
			report.Skipped++
			continue
		}

		// 2. Fetch & Parse Feed
		httpReq, _ := http.NewRequestWithContext(r.Context(), http.MethodGet, feedURL, nil)
		httpReq.Header.Set("User-Agent", "KoalaCast/1.0 (+https://github.com/Shik3i/KoalaCast)")

		resp, err := client.Do(httpReq)
		if err != nil || resp.StatusCode != http.StatusOK {
			reason := "HTTP request failed"
			if resp != nil {
				reason = fmt.Sprintf("HTTP status %d", resp.StatusCode)
				resp.Body.Close()
			}
			report.Failures = append(report.Failures, OPMLImportError{URL: feedURL, Reason: reason})
			report.Skipped++
			continue
		}

		limitReader := rss.LimitResponseBody(resp.Body, h.MaxResponseB)
		parsedFeed, err := rss.ParseFeedXML(limitReader)
		resp.Body.Close()

		if err != nil {
			report.Failures = append(report.Failures, OPMLImportError{URL: feedURL, Reason: "XML parse error: " + err.Error()})
			report.Skipped++
			continue
		}

		// 3. Persist Podcast & Subscription
		podcastID := uuid.New().String()
		nowMs := time.Now().UnixMilli()

		explicitInt := 0
		if parsedFeed.Explicit {
			explicitInt = 1
		}

		tx, err := h.DB.SQL.BeginTx(r.Context(), nil)
		if err != nil {
			continue
		}

		var existingID string
		err = tx.QueryRowContext(r.Context(), "SELECT id FROM podcasts WHERE feed_url = ?", feedURL).Scan(&existingID)
		if err == nil {
			podcastID = existingID
		} else {
			_, err = tx.ExecContext(r.Context(), `
				INSERT INTO podcasts (id, feed_url, title, description, author, artwork_url, link, language, explicit, copyright, update_frequency_ms, last_fetch_attempt_at, last_successful_fetch_at, next_scheduled_fetch_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 86400000, ?, ?, ?, ?, ?)
			`, podcastID, feedURL, parsedFeed.Title, parsedFeed.Description, parsedFeed.Author, parsedFeed.ArtworkURL, parsedFeed.Link, parsedFeed.Language, explicitInt, parsedFeed.Copyright, nowMs, nowMs, nowMs+86400000, nowMs, nowMs)
			if err != nil {
				tx.Rollback()
				continue
			}
		}

		if authUser != nil {
			_, _ = tx.ExecContext(r.Context(), `
				INSERT INTO subscriptions (user_id, podcast_id, created_at, updated_at, is_deleted, sync_version)
				VALUES (?, ?, ?, ?, 0, 1)
				ON CONFLICT(user_id, podcast_id) DO UPDATE SET is_deleted = 0
			`, authUser.ID, podcastID, nowMs, nowMs)
		}

		if err := tx.Commit(); err != nil {
			report.Failures = append(report.Failures, OPMLImportError{URL: feedURL, Reason: "failed to persist feed"})
			report.Skipped++
			continue
		}
		report.Imported++
		report.Podcasts = append(report.Podcasts, OPMLImportedPodcast{
			ID:         podcastID,
			Title:      parsedFeed.Title,
			FeedURL:    feedURL,
			ArtworkURL: parsedFeed.ArtworkURL,
		})
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

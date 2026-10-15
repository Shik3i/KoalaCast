package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"
)

const (
	inboxBatchMaxFeeds       = 100
	inboxBatchDefaultPerFeed = 15
	inboxBatchMaxPerFeed     = 50
	inboxBatchMaxBodyBytes   = 64 << 10
	inboxBatchConcurrency    = 6
	inboxBatchDeadline       = 12 * time.Second
	inboxBatchMaxIDLength    = 256
)

type inboxBatchFeed struct {
	PodcastID string `json:"podcast_id"`
	Since     int64  `json:"since"`
}

type inboxBatchRequest struct {
	Feeds        []inboxBatchFeed `json:"feeds"`
	LimitPerFeed int              `json:"limit_per_feed"`
}

// inboxEpisode carries only what the Inbox renders; the long show notes
// (content_encoded) and technical enclosure fields stay on the per-episode route.
type inboxEpisode struct {
	ID           string `json:"id"`
	PodcastID    string `json:"podcast_id"`
	Title        string `json:"title"`
	Description  string `json:"description"`
	PubDate      int64  `json:"pub_date"`
	DurationMS   int64  `json:"duration_ms"`
	EnclosureURL string `json:"enclosure_url"`
	ArtworkURL   string `json:"artwork_url"`
}

type inboxBatchFeedResult struct {
	PodcastID string         `json:"podcast_id"`
	Found     bool           `json:"found"`
	Episodes  []inboxEpisode `json:"episodes"`
}

// InboxBatch answers the Inbox for many feeds in one request. Each feed behaves
// exactly like GET /podcasts/{id}/episodes?since=…: the same on-demand refresh
// (only when the stored copy is older than onDemandRefreshAge, so ordinary
// subscriptions are never refreshed unconditionally), the same inclusive `since`
// watermark and newest-first order. Results are grouped per feed because the
// watermarks are per feed: a single global page could cut off the new episodes
// of a quiet show behind the backlog of a busy one and the client would never see
// them. A feed the server does not know is reported with found=false so the client
// can resolve it through the per-feed route.
func (h *PodcastHandler) InboxBatch(w http.ResponseWriter, r *http.Request) {
	var req inboxBatchRequest
	if err := decodeLimitedJSON(w, r, inboxBatchMaxBodyBytes, &req); err != nil {
		writeInboxError(w, "invalid request body")
		return
	}
	if len(req.Feeds) == 0 || len(req.Feeds) > inboxBatchMaxFeeds {
		writeInboxError(w, "feeds must contain between 1 and 100 entries")
		return
	}
	limit := inboxBatchDefaultPerFeed
	if req.LimitPerFeed != 0 {
		if req.LimitPerFeed < 1 || req.LimitPerFeed > inboxBatchMaxPerFeed {
			writeInboxError(w, "limit_per_feed must be between 1 and 50")
			return
		}
		limit = req.LimitPerFeed
	}

	// Validate and de-duplicate while keeping the caller's order. A repeated id
	// keeps the smaller watermark so no episode the caller lacks is skipped.
	order := make([]string, 0, len(req.Feeds))
	since := make(map[string]int64, len(req.Feeds))
	for _, feed := range req.Feeds {
		if feed.PodcastID == "" || len(feed.PodcastID) > inboxBatchMaxIDLength || feed.Since < 0 {
			writeInboxError(w, "each feed needs a podcast_id and a non-negative since")
			return
		}
		if previous, seen := since[feed.PodcastID]; seen {
			if feed.Since < previous {
				since[feed.PodcastID] = feed.Since
			}
			continue
		}
		since[feed.PodcastID] = feed.Since
		order = append(order, feed.PodcastID)
	}

	// Refreshing can take a while; extend the write deadline past the server
	// default (best effort) and bound the whole batch.
	if rc := http.NewResponseController(w); rc != nil {
		_ = rc.SetWriteDeadline(time.Now().Add(inboxBatchDeadline + 15*time.Second))
	}
	ctx, cancel := context.WithTimeout(r.Context(), inboxBatchDeadline)
	defer cancel()

	results := make([]inboxBatchFeedResult, len(order))
	slots := make(chan struct{}, inboxBatchConcurrency)
	var wg sync.WaitGroup
	for i, podcastID := range order {
		wg.Add(1)
		slots <- struct{}{}
		go func(i int, podcastID string) {
			defer wg.Done()
			defer func() { <-slots }()
			results[i] = h.inboxFeed(ctx, podcastID, since[podcastID], limit)
		}(i, podcastID)
	}
	wg.Wait()

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{"feeds": results})
}

func (h *PodcastHandler) inboxFeed(ctx context.Context, podcastID string, since int64, limit int) inboxBatchFeedResult {
	result := inboxBatchFeedResult{PodcastID: podcastID, Episodes: []inboxEpisode{}}
	var exists int
	if err := h.DB.SQL.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM podcasts WHERE id = ?)", podcastID).Scan(&exists); err != nil || exists == 0 {
		return result
	}
	result.Found = true
	h.refreshPodcastOnDemand(ctx, podcastID)

	query := `
		SELECT id, podcast_id, title, description, pub_date, duration_ms, enclosure_url, artwork_url
		FROM episodes
		WHERE podcast_id = ?`
	args := []any{podcastID}
	if since > 0 {
		// Inclusive, like the per-feed route: clients de-duplicate by episode id.
		query += " AND pub_date >= ?"
		args = append(args, since)
	}
	query += " ORDER BY pub_date DESC LIMIT ?"
	args = append(args, limit)
	// A deadline that expired during the refresh must not hide the stored rows.
	rows, err := h.DB.SQL.QueryContext(context.WithoutCancel(ctx), query, args...)
	if err != nil {
		return result
	}
	defer rows.Close()
	for rows.Next() {
		var ep inboxEpisode
		if err := rows.Scan(&ep.ID, &ep.PodcastID, &ep.Title, &ep.Description, &ep.PubDate, &ep.DurationMS, &ep.EnclosureURL, &ep.ArtworkURL); err == nil {
			result.Episodes = append(result.Episodes, ep)
		}
	}
	_ = rows.Err()
	return result
}

func writeInboxError(w http.ResponseWriter, message string) {
	writeJSON(w, http.StatusBadRequest, map[string]string{"error": message})
}

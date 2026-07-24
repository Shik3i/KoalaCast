package handlers

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/itunes"
	"github.com/Shik3i/KoalaCast/services/api/internal/podcastindex"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	"github.com/Shik3i/KoalaCast/services/api/internal/worker"
	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
)

type PodcastHandler struct {
	DB           *db.DB
	PodcastIndex *podcastindex.Client
	ITunes       *itunes.ITunesClient
	Worker       *worker.FeedWorker
	MaxResponseB int64
}

type AddFeedRequest struct {
	FeedURL string `json:"feed_url"`
}

type PodcastResponse struct {
	ID                    string `json:"id"`
	FeedURL               string `json:"feed_url"`
	Title                 string `json:"title"`
	Description           string `json:"description"`
	Author                string `json:"author"`
	ArtworkURL            string `json:"artwork_url"`
	Link                  string `json:"link"`
	Language              string `json:"language"`
	Explicit              bool   `json:"explicit"`
	Copyright             string `json:"copyright"`
	LastSuccessfulFetchAt int64  `json:"last_successful_fetch_at"`
	EpisodeCount          int    `json:"episode_count"`
}

type EpisodeResponse struct {
	ID              string `json:"id"`
	PodcastID       string `json:"podcast_id"`
	GUID            string `json:"guid"`
	Title           string `json:"title"`
	Description     string `json:"description"`
	ContentEncoded  string `json:"content_encoded"`
	PubDate         int64  `json:"pub_date"`
	HasPubDate      bool   `json:"has_pub_date"`
	DurationMS      int64  `json:"duration_ms"`
	EnclosureURL    string `json:"enclosure_url"`
	EnclosureType   string `json:"enclosure_type"`
	EnclosureLength int64  `json:"enclosure_length"`
	ArtworkURL      string `json:"artwork_url"`
	EpisodeNumber   int    `json:"episode_number"`
	SeasonNumber    int    `json:"season_number"`
	EpisodeType     string `json:"episode_type"`
	Explicit        bool   `json:"explicit"`
	Link            string `json:"link"`
}

func (h *PodcastHandler) Search(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query().Get("q")
	if q == "" {
		http.Error(w, `{"error":"search query 'q' parameter is required"}`, http.StatusBadRequest)
		return
	}

	var results interface{}
	var err error

	if h.PodcastIndex.IsConfigured() {
		results, err = h.PodcastIndex.Search(q)
	} else {
		// iTunes Search API fallback (access to millions of podcasts with HD artwork)
		if h.ITunes == nil {
			h.ITunes = itunes.NewITunesClient()
		}
		results, err = h.ITunes.SearchPodcasts(q, 50)
	}

	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"search_available": true,
		"results":          results,
	})
}

func (h *PodcastHandler) Discover(w http.ResponseWriter, r *http.Request) {
	if h.ITunes == nil {
		h.ITunes = itunes.NewITunesClient()
	}

	// Optional query params let the client pull a genre-specific chart (so the
	// category chips return full lists), a regional storefront, and more results.
	category := r.URL.Query().Get("category")
	region := r.URL.Query().Get("region")
	limit := 60
	if l, convErr := strconv.Atoi(r.URL.Query().Get("limit")); convErr == nil && l > 0 {
		limit = l
	}
	genreID := itunes.GenreIDForCategory(category)

	topPodcasts, err := h.ITunes.FetchTopChart(region, genreID, limit)
	if err != nil || len(topPodcasts) == 0 {
		var dbPods []PodcastResponse
		rows, errDB := h.DB.SQL.QueryContext(r.Context(), "SELECT id, feed_url, title, description, author, artwork_url, link, language, explicit, copyright, last_successful_fetch_at FROM podcasts LIMIT ?", limit)
		if errDB == nil {
			defer rows.Close()
			for rows.Next() {
				var p PodcastResponse
				var lastFetch sql.NullInt64
				_ = rows.Scan(&p.ID, &p.FeedURL, &p.Title, &p.Description, &p.Author, &p.ArtworkURL, &p.Link, &p.Language, &p.Explicit, &p.Copyright, &lastFetch)
				if lastFetch.Valid {
					p.LastSuccessfulFetchAt = lastFetch.Int64
				}
				dbPods = append(dbPods, p)
			}
		}
		topPodcasts = make([]itunes.PodcastResult, 0, len(dbPods))
		for _, p := range dbPods {
			topPodcasts = append(topPodcasts, itunes.PodcastResult{
				ID:          p.ID,
				Title:       p.Title,
				Author:      p.Author,
				FeedURL:     p.FeedURL,
				ArtworkURL:  p.ArtworkURL,
				Description: p.Description,
			})
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "ok",
		"results": topPodcasts,
		"total":   len(topPodcasts),
	})
}

// ingestError carries an HTTP status code alongside a message so callers can
// translate a failed feed ingestion into the right response.
type ingestError struct {
	Status int
	Msg    string
}

func (e *ingestError) Error() string { return e.Msg }

var numericIDPattern = regexp.MustCompile(`^\d+$`)

func (h *PodcastHandler) AddFeed(w http.ResponseWriter, r *http.Request) {
	var req AddFeedRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FeedURL == "" {
		http.Error(w, `{"error":"valid 'feed_url' is required"}`, http.StatusBadRequest)
		return
	}

	podcastID, err := h.ingestFeedURL(r.Context(), req.FeedURL)
	if err != nil {
		var ie *ingestError
		status := http.StatusInternalServerError
		if errors.As(err, &ie) {
			status = ie.Status
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}

	h.getAndReturnPodcast(w, r, podcastID)
}

// ingestFeedURL validates, fetches, parses and persists a feed (with its
// episodes), returning the stored podcast ID. A feed that is already stored
// *with* episodes is returned as-is without a re-fetch; one that exists but has
// zero episodes (e.g. a prior partial or transient-failure ingest) is re-fetched
// and backfilled rather than being served as an empty shell forever.
// Errors are *ingestError with an HTTP status hint.
func (h *PodcastHandler) ingestFeedURL(ctx context.Context, feedURL string) (string, error) {
	// Validate URL & SSRF checks
	if err := rss.ValidateURL(feedURL); err != nil {
		return "", &ingestError{Status: http.StatusBadRequest, Msg: err.Error()}
	}

	// Look for an existing record; only short-circuit if it already has episodes.
	var existingID string
	if err := h.DB.SQL.QueryRowContext(ctx, "SELECT id FROM podcasts WHERE feed_url = ?", feedURL).Scan(&existingID); err == nil {
		var epCount int
		_ = h.DB.SQL.QueryRowContext(ctx, "SELECT COUNT(*) FROM episodes WHERE podcast_id = ?", existingID).Scan(&epCount)
		if epCount > 0 {
			return existingID, nil
		}
	}

	// Fetch & Parse RSS/Atom Feed using Safe HTTP Client
	client := rss.NewSafeHTTPClient(rss.SafeTransportConfig{ConnectTimeout: 10 * time.Second})
	httpReq, _ := http.NewRequestWithContext(ctx, http.MethodGet, feedURL, nil)
	httpReq.Header.Set("User-Agent", "KoalaCast/1.0 (+https://github.com/Shik3i/KoalaCast)")

	resp, err := client.Do(httpReq)
	if err != nil {
		return "", &ingestError{Status: http.StatusBadRequest, Msg: "failed to fetch feed: " + err.Error()}
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", &ingestError{Status: http.StatusBadRequest, Msg: "feed URL returned status " + strconv.Itoa(resp.StatusCode)}
	}

	limitReader := rss.LimitResponseBody(resp.Body, h.MaxResponseB)
	parsedFeed, err := rss.ParseFeedXML(limitReader)
	if err != nil {
		return "", &ingestError{Status: http.StatusBadRequest, Msg: "failed to parse feed XML: " + err.Error()}
	}

	podcastID := existingID
	if podcastID == "" {
		podcastID = uuid.New().String()
	}
	nowMs := time.Now().UnixMilli()

	explicitInt := 0
	if parsedFeed.Explicit {
		explicitInt = 1
	}

	tx, err := h.DB.SQL.BeginTx(ctx, nil)
	if err != nil {
		return "", &ingestError{Status: http.StatusInternalServerError, Msg: "database transaction error"}
	}
	defer tx.Rollback()

	if existingID == "" {
		_, err = tx.ExecContext(ctx, `
			INSERT INTO podcasts (
				id, feed_url, title, description, author, artwork_url, link, language,
				explicit, copyright, update_frequency_ms, last_fetch_attempt_at,
				last_successful_fetch_at, next_scheduled_fetch_at, created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, podcastID, feedURL, parsedFeed.Title, parsedFeed.Description, parsedFeed.Author,
			parsedFeed.ArtworkURL, parsedFeed.Link, parsedFeed.Language, explicitInt, parsedFeed.Copyright,
			86400000, nowMs, nowMs, nowMs+86400000, nowMs, nowMs)
	} else {
		// Refresh metadata on the existing (previously episode-less) record.
		_, err = tx.ExecContext(ctx, `
			UPDATE podcasts SET title = ?, description = ?, author = ?, artwork_url = ?,
				link = ?, language = ?, explicit = ?, copyright = ?,
				last_successful_fetch_at = ?, updated_at = ?
			WHERE id = ?
		`, parsedFeed.Title, parsedFeed.Description, parsedFeed.Author, parsedFeed.ArtworkURL,
			parsedFeed.Link, parsedFeed.Language, explicitInt, parsedFeed.Copyright,
			nowMs, nowMs, podcastID)
	}
	if err != nil {
		return "", &ingestError{Status: http.StatusInternalServerError, Msg: "failed to save podcast: " + err.Error()}
	}

	// Insert Episodes
	for _, ep := range parsedFeed.Episodes {
		var pubDateUnix int64
		if ep.HasPubDate {
			pubDateUnix = ep.PubDate.Unix()
		}

		hasPubDateInt := 0
		if ep.HasPubDate {
			hasPubDateInt = 1
		}

		epExplicit := 0
		if ep.Explicit {
			epExplicit = 1
		}

		epID := uuid.New().String()
		_, err = tx.ExecContext(ctx, `
			INSERT OR IGNORE INTO episodes (
				id, podcast_id, stable_identity_key, guid, fallback_hash, title, description,
				content_encoded, pub_date, has_pub_date, duration_ms, enclosure_url,
				enclosure_type, enclosure_length, artwork_url, episode_number, season_number,
				episode_type, explicit, link, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, epID, podcastID, ep.StableKey, ep.GUID, ep.FallbackHash, ep.Title, ep.Description,
			ep.ContentEncoded, pubDateUnix, hasPubDateInt, ep.DurationMS, ep.EnclosureURL,
			ep.EnclosureType, ep.EnclosureLength, ep.ArtworkURL, ep.EpisodeNumber, ep.SeasonNumber,
			ep.EpisodeType, epExplicit, ep.Link, nowMs)
		if err != nil {
			// Ignore individual episode duplicate collisions
			continue
		}
	}

	if err := tx.Commit(); err != nil {
		return "", &ingestError{Status: http.StatusInternalServerError, Msg: "failed to commit transaction"}
	}

	return podcastID, nil
}

func (h *PodcastHandler) GetPodcast(w http.ResponseWriter, r *http.Request) {
	podcastID := chi.URLParam(r, "id")
	h.getAndReturnPodcast(w, r, podcastID)
}

// resolveITunesID turns a numeric iTunes collection ID into a stored podcast ID
// by looking up its feed URL and ingesting it. Returns false for non-numeric IDs
// or when resolution/ingestion fails.
func (h *PodcastHandler) resolveITunesID(ctx context.Context, id string) (string, bool) {
	if !numericIDPattern.MatchString(id) {
		return "", false
	}
	if h.ITunes == nil {
		h.ITunes = itunes.NewITunesClient()
	}

	feedURL, err := h.ITunes.LookupFeedURL(id)
	if err != nil || feedURL == "" {
		return "", false
	}

	podcastID, err := h.ingestFeedURL(ctx, feedURL)
	if err != nil {
		return "", false
	}
	return podcastID, true
}

func (h *PodcastHandler) getAndReturnPodcast(w http.ResponseWriter, r *http.Request, id string) {
	var pod PodcastResponse
	var explicitInt int

	err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT p.id, p.feed_url, p.title, p.description, p.author, p.artwork_url, p.link,
		       p.language, p.explicit, p.copyright, p.last_successful_fetch_at,
		       (SELECT COUNT(*) FROM episodes e WHERE e.podcast_id = p.id) as episode_count
		FROM podcasts p
		WHERE p.id = ?
	`, id).Scan(
		&pod.ID, &pod.FeedURL, &pod.Title, &pod.Description, &pod.Author, &pod.ArtworkURL,
		&pod.Link, &pod.Language, &explicitInt, &pod.Copyright, &pod.LastSuccessfulFetchAt, &pod.EpisodeCount,
	)

	if err == sql.ErrNoRows {
		// A numeric ID is an iTunes collection ID from Discover/Top Charts, which
		// carries no feed URL. Resolve it to a feed via the iTunes Lookup API and
		// ingest it on demand, then serve the freshly stored podcast.
		if resolvedID, ok := h.resolveITunesID(r.Context(), id); ok {
			h.getAndReturnPodcast(w, r, resolvedID)
			return
		}
		http.Error(w, `{"error":"podcast not found"}`, http.StatusNotFound)
		return
	} else if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	pod.Explicit = (explicitInt == 1)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(pod)
}

func (h *PodcastHandler) GetEpisodes(w http.ResponseWriter, r *http.Request) {
	podcastID := chi.URLParam(r, "id")

	limitStr := r.URL.Query().Get("limit")
	limit := 50
	if l, err := strconv.Atoi(limitStr); err == nil && l > 0 && l <= 200 {
		limit = l
	}

	offsetStr := r.URL.Query().Get("offset")
	offset := 0
	if o, err := strconv.Atoi(offsetStr); err == nil && o >= 0 {
		offset = o
	}

	rows, err := h.DB.SQL.QueryContext(r.Context(), `
		SELECT id, podcast_id, guid, title, description, content_encoded, pub_date,
		       has_pub_date, duration_ms, enclosure_url, enclosure_type, enclosure_length,
		       artwork_url, episode_number, season_number, episode_type, explicit, link
		FROM episodes
		WHERE podcast_id = ?
		ORDER BY pub_date DESC
		LIMIT ? OFFSET ?
	`, podcastID, limit, offset)
	if err != nil {
		http.Error(w, `{"error":"failed to query episodes"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	episodes := make([]EpisodeResponse, 0)
	for rows.Next() {
		var ep EpisodeResponse
		var hasPubDateInt, explicitInt int
		err := rows.Scan(
			&ep.ID, &ep.PodcastID, &ep.GUID, &ep.Title, &ep.Description, &ep.ContentEncoded,
			&ep.PubDate, &hasPubDateInt, &ep.DurationMS, &ep.EnclosureURL, &ep.EnclosureType,
			&ep.EnclosureLength, &ep.ArtworkURL, &ep.EpisodeNumber, &ep.SeasonNumber,
			&ep.EpisodeType, &explicitInt, &ep.Link,
		)
		if err == nil {
			ep.HasPubDate = (hasPubDateInt == 1)
			ep.Explicit = (explicitInt == 1)
			episodes = append(episodes, ep)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"episodes": episodes,
		"count":    len(episodes),
		"limit":    limit,
		"offset":   offset,
	})
}

func (h *PodcastHandler) GetEpisode(w http.ResponseWriter, r *http.Request) {
	episodeID := chi.URLParam(r, "id")

	var ep EpisodeResponse
	var hasPubDateInt, explicitInt int

	err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT id, podcast_id, guid, title, description, content_encoded, pub_date,
		       has_pub_date, duration_ms, enclosure_url, enclosure_type, enclosure_length,
		       artwork_url, episode_number, season_number, episode_type, explicit, link
		FROM episodes
		WHERE id = ?
	`, episodeID).Scan(
		&ep.ID, &ep.PodcastID, &ep.GUID, &ep.Title, &ep.Description, &ep.ContentEncoded,
		&ep.PubDate, &hasPubDateInt, &ep.DurationMS, &ep.EnclosureURL, &ep.EnclosureType,
		&ep.EnclosureLength, &ep.ArtworkURL, &ep.EpisodeNumber, &ep.SeasonNumber,
		&ep.EpisodeType, &explicitInt, &ep.Link,
	)

	if err == sql.ErrNoRows {
		http.Error(w, `{"error":"episode not found"}`, http.StatusNotFound)
		return
	} else if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	ep.HasPubDate = (hasPubDateInt == 1)
	ep.Explicit = (explicitInt == 1)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(ep)
}

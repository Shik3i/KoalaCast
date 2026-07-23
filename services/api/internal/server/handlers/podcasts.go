package handlers

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/podcastindex"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	"github.com/Shik3i/KoalaCast/services/api/internal/worker"
	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
)

type PodcastHandler struct {
	DB            *db.DB
	PodcastIndex  *podcastindex.Client
	Worker        *worker.FeedWorker
	MaxResponseB  int64
}

type AddFeedRequest struct {
	FeedURL string `json:"feed_url"`
}

type PodcastResponse struct {
	ID                     string `json:"id"`
	FeedURL                string `json:"feed_url"`
	Title                  string `json:"title"`
	Description            string `json:"description"`
	Author                 string `json:"author"`
	ArtworkURL             string `json:"artwork_url"`
	Link                   string `json:"link"`
	Language               string `json:"language"`
	Explicit               bool   `json:"explicit"`
	Copyright              string `json:"copyright"`
	LastSuccessfulFetchAt  int64  `json:"last_successful_fetch_at"`
	EpisodeCount           int    `json:"episode_count"`
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

	if !h.PodcastIndex.IsConfigured() {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"search_available": false,
			"message":          "Podcast Index search API credentials are not configured. You can still add podcasts directly by pasting an RSS feed URL.",
			"results":          []interface{}{},
		})
		return
	}

	results, err := h.PodcastIndex.Search(q)
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

func (h *PodcastHandler) AddFeed(w http.ResponseWriter, r *http.Request) {
	var req AddFeedRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FeedURL == "" {
		http.Error(w, `{"error":"valid 'feed_url' is required"}`, http.StatusBadRequest)
		return
	}

	// Validate URL & SSRF checks
	if err := rss.ValidateURL(req.FeedURL); err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}

	ctx := r.Context()

	// Check if feed already exists in podcasts or podcast_aliases
	var existingID string
	err := h.DB.SQL.QueryRowContext(ctx, "SELECT id FROM podcasts WHERE feed_url = ?", req.FeedURL).Scan(&existingID)
	if err == nil {
		h.getAndReturnPodcast(w, r, existingID)
		return
	}

	// Fetch & Parse RSS/Atom Feed using Safe HTTP Client
	client := rss.NewSafeHTTPClient(rss.SafeTransportConfig{ConnectTimeout: 10 * time.Second})
	httpReq, _ := http.NewRequestWithContext(ctx, http.MethodGet, req.FeedURL, nil)
	httpReq.Header.Set("User-Agent", "KoalaCast/1.0 (+https://github.com/Shik3i/KoalaCast)")

	resp, err := client.Do(httpReq)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": "failed to fetch feed: " + err.Error()})
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": "feed URL returned status " + strconv.Itoa(resp.StatusCode)})
		return
	}

	limitReader := rss.LimitResponseBody(resp.Body, h.MaxResponseB)
	parsedFeed, err := rss.ParseFeedXML(limitReader)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": "failed to parse feed XML: " + err.Error()})
		return
	}

	podcastID := uuid.New().String()
	nowMs := time.Now().UnixMilli()

	explicitInt := 0
	if parsedFeed.Explicit {
		explicitInt = 1
	}

	tx, err := h.DB.SQL.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, `{"error":"database transaction error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	_, err = tx.ExecContext(ctx, `
		INSERT INTO podcasts (
			id, feed_url, title, description, author, artwork_url, link, language,
			explicit, copyright, update_frequency_ms, last_fetch_attempt_at,
			last_successful_fetch_at, next_scheduled_fetch_at, created_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`, podcastID, req.FeedURL, parsedFeed.Title, parsedFeed.Description, parsedFeed.Author,
		parsedFeed.ArtworkURL, parsedFeed.Link, parsedFeed.Language, explicitInt, parsedFeed.Copyright,
		86400000, nowMs, nowMs, nowMs+86400000, nowMs, nowMs)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": "failed to save podcast: " + err.Error()})
		return
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
			INSERT INTO episodes (
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
		http.Error(w, `{"error":"failed to commit transaction"}`, http.StatusInternalServerError)
		return
	}

	h.getAndReturnPodcast(w, r, podcastID)
}

func (h *PodcastHandler) GetPodcast(w http.ResponseWriter, r *http.Request) {
	podcastID := chi.URLParam(r, "id")
	h.getAndReturnPodcast(w, r, podcastID)
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

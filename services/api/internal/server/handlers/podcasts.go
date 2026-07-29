package handlers

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/itunes"
	"github.com/Shik3i/KoalaCast/services/api/internal/lang"
	"github.com/Shik3i/KoalaCast/services/api/internal/podcastindex"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	"github.com/Shik3i/KoalaCast/services/api/internal/worker"
	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"golang.org/x/sync/singleflight"
)

type PodcastHandler struct {
	DB             *db.DB
	PodcastIndex   *podcastindex.Client
	ITunes         *itunes.ITunesClient
	Worker         *worker.FeedWorker
	MaxResponseB   int64
	FeedHTTPClient *http.Client
	feedIngest     singleflight.Group
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
	ID              string           `json:"id"`
	PodcastID       string           `json:"podcast_id"`
	GUID            string           `json:"guid"`
	Title           string           `json:"title"`
	Description     string           `json:"description"`
	ContentEncoded  string           `json:"content_encoded"`
	PubDate         int64            `json:"pub_date"`
	HasPubDate      bool             `json:"has_pub_date"`
	DurationMS      int64            `json:"duration_ms"`
	EnclosureURL    string           `json:"enclosure_url"`
	EnclosureType   string           `json:"enclosure_type"`
	EnclosureLength int64            `json:"enclosure_length"`
	ArtworkURL      string           `json:"artwork_url"`
	EpisodeNumber   int              `json:"episode_number"`
	SeasonNumber    int              `json:"season_number"`
	EpisodeType     string           `json:"episode_type"`
	Explicit        bool             `json:"explicit"`
	Link            string           `json:"link"`
	Transcripts     []transcriptItem `json:"transcripts"`
	ChaptersURL     string           `json:"chapters_url"`
}

// transcriptItem is a publisher-provided transcript reference (Podcasting 2.0).
type transcriptItem struct {
	URL  string `json:"url"`
	Type string `json:"type"`
}

func (h *PodcastHandler) Search(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query().Get("q")
	if q == "" {
		http.Error(w, `{"error":"search query 'q' parameter is required"}`, http.StatusBadRequest)
		return
	}

	region := r.URL.Query().Get("region")
	if region == "" {
		region = r.URL.Query().Get("country")
	}
	// Both filters are optional and independent: the client pre-selects the
	// listener's settings languages but can clear them to search everything.
	languages := lang.ParseList(r.URL.Query().Get("languages"))
	category := r.URL.Query().Get("category")

	var results []itunes.PodcastResult
	var err error
	provider := "itunes"

	if h.PodcastIndex != nil && h.PodcastIndex.IsConfigured() {
		provider = "podcastindex"
		var piResults []podcastindex.SearchResult
		piResults, err = h.PodcastIndex.Search(q)
		if err == nil {
			results = make([]itunes.PodcastResult, 0, len(piResults))
			for _, p := range piResults {
				feedURL := p.URL
				if feedURL == "" {
					feedURL = p.OriginalURL
				}
				language := lang.Normalize(p.Language)
				if language == "" {
					language = lang.Detect(p.Title + " " + p.Description)
				}
				cats := p.CategoryList()
				cat := ""
				if len(cats) > 0 {
					cat = cats[0]
				}
				results = append(results, itunes.PodcastResult{
					ID:          strconv.FormatInt(p.ID, 10),
					Title:       p.Title,
					Author:      p.Author,
					FeedURL:     feedURL,
					ArtworkURL:  p.Artwork,
					Category:    cat,
					Categories:  cats,
					Description: p.Description,
					Language:    language,
				})
			}
		}
	}

	// Podcast Index credentials can be syntactically present but expired or
	// rejected upstream. Search must remain available in that state: fall back
	// to Apple's public podcast catalogue just as we do when no credentials are
	// configured, instead of turning a provider-specific 401 into an app-wide
	// connection error.
	if provider == "itunes" || err != nil {
		provider = "itunes"
		if h.ITunes == nil {
			h.ITunes = itunes.NewITunesClient()
		}
		results, err = h.ITunes.SearchPodcastsWithCountry(q, region, 50)
	}

	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}

	if len(languages) > 0 {
		h.resolveLanguages(r.Context(), results)
		results = filterByLanguage(results, languages)
	}
	results = filterByCategory(results, category)
	if results == nil {
		results = []itunes.PodcastResult{}
	}

	writePublicJSON(w, r, map[string]interface{}{
		"search_available": true,
		"provider":         provider,
		"results":          results,
	}, "public, max-age=1800, stale-while-revalidate=3600")
}

func (h *PodcastHandler) Discover(w http.ResponseWriter, r *http.Request) {
	if h.ITunes == nil {
		h.ITunes = itunes.NewITunesClient()
	}

	// Optional query params let the client pull a genre-specific chart (so the
	// category chips return full lists), a regional storefront, and more results.
	category := r.URL.Query().Get("category")
	region := r.URL.Query().Get("region")
	// Spoken-language filter, independent of the storefront region: the German
	// storefront carries plenty of English shows, so region alone never yields
	// a German-only chart.
	languages := lang.ParseList(r.URL.Query().Get("languages"))
	// Older clients sent a single content language without its matching
	// storefront. Falling back to the US chart and then filtering it could
	// legitimately produce zero results (notably for German). Infer the chart
	// only when the request is unambiguous; current clients send region.
	if region == "" && len(languages) == 1 {
		region = lang.StorefrontRegion(languages[0])
	}
	limit := 60
	if l, convErr := strconv.Atoi(r.URL.Query().Get("limit")); convErr == nil && l > 0 {
		// Cap the upper bound so a client can't request an unbounded chart size
		// (which fans out to the upstream chart APIs and a large DB scan).
		if l > 100 {
			l = 100
		}
		limit = l
	}
	// Pull extra rows upstream when filtering, so a mixed-language chart still
	// fills a page after the non-matching entries are dropped.
	fetchLimit := limit
	if len(languages) > 0 {
		fetchLimit = limit * languageFilterOverfetch
		if fetchLimit > 200 {
			fetchLimit = 200
		}
	}
	var topPodcasts []itunes.PodcastResult

	// Prefer Podcast Index trending when configured (broader, fresher than the
	// iTunes storefront charts), then fall back to iTunes charts, then the DB.
	if h.PodcastIndex != nil && h.PodcastIndex.IsConfigured() {
		piCat := category
		if piCat == "All" {
			piCat = ""
		}
		// Podcast Index caps a trending request at 100 and silently falls back to
		// its own default above that, so clamp rather than overshoot.
		piLimit := fetchLimit
		if piLimit > 100 {
			piLimit = 100
		}
		if piResults, piErr := h.PodcastIndex.Trending(piCat, piLimit); piErr == nil {
			for _, p := range piResults {
				art := p.Artwork
				if art == "" {
					art = p.Image
				}
				feed := p.URL
				if feed == "" {
					feed = p.OriginalURL
				}
				cats := p.CategoryList()
				cat := category
				if len(cats) > 0 {
					cat = cats[0]
				}
				// Podcast Index reports the feed's own <language>; fall back to
				// detection only when it left the field empty.
				language := lang.Normalize(p.Language)
				if language == "" {
					language = lang.Detect(p.Title + " " + p.Description)
				}
				topPodcasts = append(topPodcasts, itunes.PodcastResult{
					ID:          strconv.FormatInt(p.ID, 10),
					Title:       p.Title,
					Author:      p.Author,
					FeedURL:     feed,
					ArtworkURL:  art,
					Category:    cat,
					Categories:  cats,
					Description: p.Description,
					Language:    language,
				})
			}
		}
	}

	if len(topPodcasts) == 0 && h.ITunes != nil {
		genreID := itunes.GenreIDForCategory(category)
		if tp, err := h.ITunes.FetchTopChart(region, genreID, fetchLimit); err == nil {
			topPodcasts = tp
			// One cached, batched Apple lookup supplies the fields omitted by
			// the legacy chart response. Failure is non-fatal: clients can still
			// fall back to feed ingestion for individual rows.
			_ = h.ITunes.EnrichLatestEpisodes(topPodcasts)
		}
	}

	if len(topPodcasts) == 0 && h.DB != nil {
		var dbPods []PodcastResponse
		rows, errDB := h.DB.SQL.QueryContext(r.Context(), "SELECT id, feed_url, title, description, author, artwork_url, link, language, explicit, copyright, last_successful_fetch_at FROM podcasts LIMIT ?", fetchLimit)
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
		for _, p := range dbPods {
			topPodcasts = append(topPodcasts, itunes.PodcastResult{
				ID:          p.ID,
				Title:       p.Title,
				Author:      p.Author,
				FeedURL:     p.FeedURL,
				ArtworkURL:  p.ArtworkURL,
				Description: p.Description,
				Language:    lang.Normalize(p.Language),
			})
		}
	}

	// Prefer the stored RSS <language> over a guess wherever the feed is known
	// locally, then drop everything the listener does not speak.
	if len(languages) > 0 {
		h.resolveLanguages(r.Context(), topPodcasts)
		topPodcasts = filterByLanguage(topPodcasts, languages)
	}
	if len(topPodcasts) > limit {
		topPodcasts = topPodcasts[:limit]
	}

	if topPodcasts == nil {
		topPodcasts = []itunes.PodcastResult{}
	}

	writePublicJSON(w, r, map[string]interface{}{
		"status":  "ok",
		"results": topPodcasts,
		"total":   len(topPodcasts),
	}, "public, max-age=14400, stale-while-revalidate=18000")
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

// IngestFeedURL exposes the same complete ingestion used by POST /podcasts/feed
// to bounded server-side batch workflows such as OPML import.
func (h *PodcastHandler) IngestFeedURL(ctx context.Context, feedURL string) (string, error) {
	return h.ingestFeedURL(ctx, feedURL)
}

// ingestFeedURL validates, fetches, parses and persists a feed (with its
// episodes), returning the stored podcast ID. A feed that is already stored
// *with* episodes is returned as-is without a re-fetch; one that exists but has
// zero episodes (e.g. a prior partial or transient-failure ingest) is re-fetched
// and backfilled rather than being served as an empty shell forever.
// Errors are *ingestError with an HTTP status hint.
func (h *PodcastHandler) ingestFeedURL(ctx context.Context, feedURL string) (string, error) {
	feedURL = strings.TrimSpace(feedURL)
	value, err, _ := h.feedIngest.Do(feedURL, func() (any, error) {
		return h.ingestFeedURLOnce(ctx, feedURL)
	})
	if err != nil {
		return "", err
	}
	return value.(string), nil
}

func (h *PodcastHandler) ingestFeedURLOnce(ctx context.Context, feedURL string) (string, error) {
	// Validate URL & SSRF checks
	if err := rss.ValidateURL(feedURL); err != nil {
		return "", &ingestError{Status: http.StatusBadRequest, Msg: err.Error()}
	}

	// Resolve both canonical feed URLs and every previously observed alias.
	existingID, err := h.resolvePodcastURL(ctx, feedURL)
	if err != nil && err != sql.ErrNoRows {
		return "", &ingestError{Status: http.StatusInternalServerError, Msg: "failed to resolve feed URL"}
	}
	if existingID != "" {
		var epCount int
		_ = h.DB.SQL.QueryRowContext(ctx, "SELECT COUNT(*) FROM episodes WHERE podcast_id = ?", existingID).Scan(&epCount)
		if epCount > 0 {
			return existingID, nil
		}
	}

	// Fetch & Parse RSS/Atom Feed using Safe HTTP Client
	client := h.FeedHTTPClient
	if client == nil {
		client = rss.NewSafeHTTPClient(rss.SafeTransportConfig{ConnectTimeout: 10 * time.Second})
	}
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
	canonicalURL := feedURL
	if resp.Request != nil && resp.Request.URL != nil {
		canonicalURL = resp.Request.URL.String()
	}
	if err := rss.ValidateURL(canonicalURL); err != nil {
		return "", &ingestError{Status: http.StatusBadRequest, Msg: "invalid canonical feed URL"}
	}

	canonicalID, err := h.resolvePodcastURL(ctx, canonicalURL)
	if err != nil && err != sql.ErrNoRows {
		return "", &ingestError{Status: http.StatusInternalServerError, Msg: "failed to resolve canonical feed URL"}
	}
	if canonicalID != "" && canonicalID != existingID {
		var epCount int
		_ = h.DB.SQL.QueryRowContext(ctx, "SELECT COUNT(*) FROM episodes WHERE podcast_id = ?", canonicalID).Scan(&epCount)
		if epCount > 0 {
			if err := h.storePodcastAliases(ctx, canonicalID, feedURL, canonicalURL); err != nil {
				return "", &ingestError{Status: http.StatusInternalServerError, Msg: "failed to save feed alias"}
			}
			return canonicalID, nil
		}
		existingID = canonicalID
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
		`, podcastID, canonicalURL, parsedFeed.Title, parsedFeed.Description, parsedFeed.Author,
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
		transcriptsJSON := ""
		if len(ep.Transcripts) > 0 {
			if b, mErr := json.Marshal(ep.Transcripts); mErr == nil {
				transcriptsJSON = string(b)
			}
		}
		_, err = tx.ExecContext(ctx, `
			INSERT OR IGNORE INTO episodes (
				id, podcast_id, stable_identity_key, guid, fallback_hash, title, description,
				content_encoded, pub_date, has_pub_date, duration_ms, enclosure_url,
				enclosure_type, enclosure_length, artwork_url, episode_number, season_number,
				episode_type, explicit, link, transcripts, chapters_url, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		`, epID, podcastID, ep.StableKey, ep.GUID, ep.FallbackHash, ep.Title, ep.Description,
			ep.ContentEncoded, pubDateUnix, hasPubDateInt, ep.DurationMS, ep.EnclosureURL,
			ep.EnclosureType, ep.EnclosureLength, ep.ArtworkURL, ep.EpisodeNumber, ep.SeasonNumber,
			ep.EpisodeType, epExplicit, ep.Link, transcriptsJSON, ep.ChaptersURL, nowMs)
		if err != nil {
			// Ignore individual episode duplicate collisions
			continue
		}
	}

	for _, aliasURL := range []string{feedURL, canonicalURL} {
		if aliasURL == "" {
			continue
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO podcast_aliases (id, alias_url, target_podcast_id, created_at)
			VALUES (?, ?, ?, ?)
			ON CONFLICT(alias_url) DO UPDATE SET target_podcast_id = excluded.target_podcast_id
		`, uuid.New().String(), aliasURL, podcastID, nowMs); err != nil {
			return "", &ingestError{Status: http.StatusInternalServerError, Msg: "failed to save feed alias"}
		}
	}

	if err := tx.Commit(); err != nil {
		return "", &ingestError{Status: http.StatusInternalServerError, Msg: "failed to commit transaction"}
	}

	return podcastID, nil
}

func (h *PodcastHandler) resolvePodcastURL(ctx context.Context, feedURL string) (string, error) {
	var podcastID string
	err := h.DB.SQL.QueryRowContext(ctx, `
		SELECT id FROM podcasts WHERE feed_url = ?
		UNION ALL
		SELECT target_podcast_id FROM podcast_aliases WHERE alias_url = ?
		LIMIT 1
	`, feedURL, feedURL).Scan(&podcastID)
	return podcastID, err
}

func (h *PodcastHandler) storePodcastAliases(ctx context.Context, podcastID string, urls ...string) error {
	tx, err := h.DB.SQL.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	nowMs := time.Now().UnixMilli()
	for _, aliasURL := range urls {
		aliasURL = strings.TrimSpace(aliasURL)
		if aliasURL == "" {
			continue
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO podcast_aliases (id, alias_url, target_podcast_id, created_at)
			VALUES (?, ?, ?, ?)
			ON CONFLICT(alias_url) DO UPDATE SET target_podcast_id = excluded.target_podcast_id
		`, uuid.New().String(), aliasURL, podcastID, nowMs); err != nil {
			return err
		}
	}
	return tx.Commit()
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

	writePublicJSON(w, r, pod, "public, max-age=18000, stale-while-revalidate=86400")
}

func (h *PodcastHandler) GetEpisodes(w http.ResponseWriter, r *http.Request) {
	podcastID := chi.URLParam(r, "id")

	// Discover hands out iTunes collection ids, because the Top Charts feed
	// carries no feed URL to resolve against. GET /podcasts/{id} already turns
	// those into stored podcasts; without the same step here the query below
	// simply matches nothing and the caller sees a show with no episodes.
	if numericIDPattern.MatchString(podcastID) {
		var exists int
		_ = h.DB.SQL.QueryRowContext(r.Context(),
			"SELECT EXISTS(SELECT 1 FROM podcasts WHERE id = ?)", podcastID).Scan(&exists)
		if exists == 0 {
			if resolved, ok := h.resolveITunesID(r.Context(), podcastID); ok {
				podcastID = resolved
			}
		}
	}

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

	since := int64(0)
	if value := r.URL.Query().Get("since"); value != "" {
		if parsed, err := strconv.ParseInt(value, 10, 64); err == nil && parsed > 0 {
			since = parsed
			offset = 0
		}
	}

	query := `
		SELECT id, podcast_id, guid, title, description, content_encoded, pub_date,
		       has_pub_date, duration_ms, enclosure_url, enclosure_type, enclosure_length,
		       artwork_url, episode_number, season_number, episode_type, explicit, link,
		       chapters_url
		FROM episodes
		WHERE podcast_id = ?
	`
	args := []any{podcastID}
	if since > 0 {
		// Inclusive by design: clients de-duplicate by episode id, so two episodes
		// published in the same second cannot make one another invisible.
		query += " AND pub_date >= ?"
		args = append(args, since)
	}
	query += `
		ORDER BY pub_date DESC
		LIMIT ? OFFSET ?
	`
	args = append(args, limit, offset)
	rows, err := h.DB.SQL.QueryContext(r.Context(), query, args...)
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
			&ep.EpisodeType, &explicitInt, &ep.Link, &ep.ChaptersURL,
		)
		if err == nil {
			ep.HasPubDate = (hasPubDateInt == 1)
			ep.Explicit = (explicitInt == 1)
			episodes = append(episodes, ep)
		}
	}
	if err := rows.Err(); err != nil {
		http.Error(w, `{"error":"error scanning episodes"}`, http.StatusInternalServerError)
		return
	}

	writePublicJSON(w, r, map[string]interface{}{
		"episodes": episodes,
		"count":    len(episodes),
		"limit":    limit,
		"offset":   offset,
		"since":    since,
	}, "public, max-age=300, stale-while-revalidate=600")
}

func (h *PodcastHandler) GetEpisode(w http.ResponseWriter, r *http.Request) {
	episodeID := chi.URLParam(r, "id")

	var ep EpisodeResponse
	var hasPubDateInt, explicitInt int
	var transcriptsJSON sql.NullString

	err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT id, podcast_id, guid, title, description, content_encoded, pub_date,
		       has_pub_date, duration_ms, enclosure_url, enclosure_type, enclosure_length,
		       artwork_url, episode_number, season_number, episode_type, explicit, link, transcripts,
		       chapters_url
		FROM episodes
		WHERE id = ?
	`, episodeID).Scan(
		&ep.ID, &ep.PodcastID, &ep.GUID, &ep.Title, &ep.Description, &ep.ContentEncoded,
		&ep.PubDate, &hasPubDateInt, &ep.DurationMS, &ep.EnclosureURL, &ep.EnclosureType,
		&ep.EnclosureLength, &ep.ArtworkURL, &ep.EpisodeNumber, &ep.SeasonNumber,
		&ep.EpisodeType, &explicitInt, &ep.Link, &transcriptsJSON, &ep.ChaptersURL,
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
	ep.Transcripts = []transcriptItem{}
	if transcriptsJSON.Valid && transcriptsJSON.String != "" {
		_ = json.Unmarshal([]byte(transcriptsJSON.String), &ep.Transcripts)
	}

	writePublicJSON(w, r, ep, "public, max-age=86400, stale-while-revalidate=86400")
}

// GetEpisodeTranscript fetches a publisher-provided transcript on demand and
// returns its raw text. It only ever fetches a URL already stored for that
// episode (never arbitrary user input), goes through the SSRF-safe HTTP client,
// and caps the response size — so it stays light and can't be abused as a proxy.
func (h *PodcastHandler) GetEpisodeTranscript(w http.ResponseWriter, r *http.Request) {
	episodeID := chi.URLParam(r, "id")
	idx := 0
	if n, e := strconv.Atoi(r.URL.Query().Get("i")); e == nil && n >= 0 {
		idx = n
	}

	var transcriptsJSON sql.NullString
	err := h.DB.SQL.QueryRowContext(r.Context(), "SELECT transcripts FROM episodes WHERE id = ?", episodeID).Scan(&transcriptsJSON)
	if err == sql.ErrNoRows {
		http.Error(w, `{"error":"episode not found"}`, http.StatusNotFound)
		return
	} else if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	var items []transcriptItem
	if transcriptsJSON.Valid && transcriptsJSON.String != "" {
		_ = json.Unmarshal([]byte(transcriptsJSON.String), &items)
	}
	if idx >= len(items) {
		http.Error(w, `{"error":"transcript not available"}`, http.StatusNotFound)
		return
	}

	target := items[idx].URL
	if err := rss.ValidateURL(target); err != nil {
		http.Error(w, `{"error":"invalid transcript url"}`, http.StatusBadRequest)
		return
	}

	client := rss.NewSafeHTTPClient(rss.SafeTransportConfig{ConnectTimeout: 10 * time.Second})
	req, _ := http.NewRequestWithContext(r.Context(), http.MethodGet, target, nil)
	req.Header.Set("User-Agent", "KoalaCast/1.0 (+https://github.com/Shik3i/KoalaCast)")
	resp, err := client.Do(req)
	if err != nil {
		http.Error(w, `{"error":"failed to fetch transcript"}`, http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		http.Error(w, `{"error":"transcript source returned an error"}`, http.StatusBadGateway)
		return
	}

	body, err := io.ReadAll(rss.LimitResponseBody(resp.Body, h.MaxResponseB))
	if err != nil {
		http.Error(w, `{"error":"failed to read transcript"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"type":    items[idx].Type,
		"content": string(body),
	})
}

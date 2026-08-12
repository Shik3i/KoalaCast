package itunes

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/lang"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

// Apple podcast genre IDs for the categories the web client exposes. Used to
// request genre-specific top charts so Discover isn't limited to the single
// overall chart (which skews to a handful of genres).
var genreIDs = map[string]int{
	"arts":                    1301,
	"business":                1321,
	"comedy":                  1303,
	"education":               1304,
	"fiction":                 1483,
	"government":              1511,
	"health & fitness":        1512,
	"health":                  1512,
	"history":                 1487,
	"kids & family":           1305,
	"kids":                    1305,
	"leisure":                 1502,
	"music":                   1310,
	"news":                    1489,
	"religion & spirituality": 1314,
	"religion":                1314,
	"science":                 1533,
	"society & culture":       1324,
	"society":                 1324,
	"sports":                  1545,
	"technology":              1318,
	"true crime":              1488,
	"tv & film":               1309,
	"tv":                      1309,
}

// GenreIDForCategory maps a UI category label to an Apple podcast genre id.
// Unknown labels and "all" return 0, meaning the overall (un-scoped) chart.
func GenreIDForCategory(category string) int {
	return genreIDs[strings.ToLower(strings.TrimSpace(category))]
}

// sanitizeRegion guards the region path segment (it is interpolated into the
// iTunes URL): only a two-letter alpha storefront code is allowed, else "us".
func sanitizeRegion(region string) string {
	region = strings.ToLower(strings.TrimSpace(region))
	if len(region) != 2 {
		return "us"
	}
	for _, r := range region {
		if r < 'a' || r > 'z' {
			return "us"
		}
	}
	return region
}

type ITunesClient struct {
	httpClient         *http.Client
	latestMu           sync.Mutex
	latestFetchMu      sync.Mutex
	latestEpisodeCache map[string]cachedLatestEpisode
}

type PodcastResult struct {
	ID                string   `json:"id"`
	Title             string   `json:"title"`
	Author            string   `json:"author"`
	FeedURL           string   `json:"feed_url"`
	ArtworkURL        string   `json:"artwork_url"`
	Category          string   `json:"category"`
	Categories        []string `json:"categories,omitempty"`
	Description       string   `json:"description"`
	Explicit          *bool    `json:"explicit"`
	LatestDurationMS  int64    `json:"latest_duration_ms,omitempty"`
	LatestPublishedAt int64    `json:"latest_published_at,omitempty"`
	// Language is a bare language code ("de", "en") or "" when unknown. iTunes
	// reports no language on either the chart or the search endpoint, so it is
	// inferred from the title and description; callers that can resolve an
	// authoritative language (RSS <language>, Podcast Index) should overwrite it.
	Language string `json:"language,omitempty"`
}

type cachedLatestEpisode struct {
	feedURL     string
	durationMS  int64
	publishedAt int64
	explicit    *bool
	expiresAt   time.Time
}

const latestEpisodeCacheTTL = 15 * time.Minute

func NewITunesClient() *ITunesClient {
	return &ITunesClient{
		httpClient:         rss.NewSafeHTTPClient(rss.SafeTransportConfig{ConnectTimeout: 10 * time.Second}),
		latestEpisodeCache: make(map[string]cachedLatestEpisode),
	}
}

// NewITunesClientWithHTTPClient allows callers to provide a custom transport,
// primarily for deterministic tests that must not depend on Apple's availability.
func NewITunesClientWithHTTPClient(httpClient *http.Client) *ITunesClient {
	if httpClient == nil {
		return NewITunesClient()
	}
	return &ITunesClient{
		httpClient:         httpClient,
		latestEpisodeCache: make(map[string]cachedLatestEpisode),
	}
}

// EnrichLatestEpisodes adds the newest episode's exact Apple duration and
// publication time to chart entries in one batched lookup. The legacy Top
// Podcasts chart omits both fields (and the feed URL), which previously forced
// the web client into one feed ingestion request per visible row.
func (c *ITunesClient) EnrichLatestEpisodes(results []PodcastResult) error {
	if len(results) == 0 {
		return nil
	}

	now := time.Now()
	missing := make([]string, 0, len(results))
	byID := make(map[string][]int, len(results))
	queued := make(map[string]bool, len(results))

	c.latestMu.Lock()
	for i := range results {
		id := results[i].ID
		if _, err := strconv.ParseInt(id, 10, 64); err != nil {
			continue
		}
		byID[id] = append(byID[id], i)
		if cached, ok := c.latestEpisodeCache[id]; ok && now.Before(cached.expiresAt) {
			applyLatestEpisode(&results[i], cached)
			continue
		}
		if !queued[id] {
			missing = append(missing, id)
			queued[id] = true
		}
	}
	c.latestMu.Unlock()

	if len(missing) == 0 {
		return nil
	}

	// Coalesce concurrent Discover requests. Re-check after acquiring the fetch
	// lock so only the first request reaches Apple; followers consume its cache.
	c.latestFetchMu.Lock()
	defer c.latestFetchMu.Unlock()
	unresolved := missing[:0]
	c.latestMu.Lock()
	for _, id := range missing {
		if cached, ok := c.latestEpisodeCache[id]; ok && now.Before(cached.expiresAt) {
			for _, index := range byID[id] {
				applyLatestEpisode(&results[index], cached)
			}
			continue
		}
		unresolved = append(unresolved, id)
	}
	c.latestMu.Unlock()
	missing = unresolved
	if len(missing) == 0 {
		return nil
	}

	reqURL := "https://itunes.apple.com/lookup?id=" + strings.Join(missing, ",") + "&entity=podcastEpisode&limit=1"
	resp, err := c.httpClient.Get(reqURL)
	if err != nil {
		return fmt.Errorf("iTunes episode lookup failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("iTunes episode lookup HTTP error status %d", resp.StatusCode)
	}

	var lookupResp struct {
		Results []struct {
			WrapperType            string `json:"wrapperType"`
			Kind                   string `json:"kind"`
			CollectionID           int64  `json:"collectionId"`
			TrackTimeMS            int64  `json:"trackTimeMillis"`
			ReleaseDate            string `json:"releaseDate"`
			FeedURL                string `json:"feedUrl"`
			CollectionExplicitness string `json:"collectionExplicitness"`
			TrackExplicitness      string `json:"trackExplicitness"`
			ContentAdvisoryRating  string `json:"contentAdvisoryRating"`
		} `json:"results"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&lookupResp); err != nil {
		return fmt.Errorf("failed to decode iTunes episode lookup JSON: %w", err)
	}

	resolved := make(map[string]cachedLatestEpisode, len(missing))
	for _, item := range lookupResp.Results {
		id := strconv.FormatInt(item.CollectionID, 10)
		meta := resolved[id]
		if rating := parseExplicitness(
			item.CollectionExplicitness,
			item.TrackExplicitness,
			item.ContentAdvisoryRating,
		); rating != nil {
			meta.explicit = rating
		}
		if item.FeedURL != "" {
			meta.feedURL = item.FeedURL
		}
		if item.WrapperType == "podcastEpisode" || item.Kind == "podcast-episode" {
			publishedAt := int64(0)
			if published, parseErr := time.Parse(time.RFC3339, item.ReleaseDate); parseErr == nil {
				publishedAt = published.UnixMilli()
			}
			if publishedAt >= meta.publishedAt {
				meta.durationMS = item.TrackTimeMS
				meta.publishedAt = publishedAt
			}
		}
		resolved[id] = meta
	}

	c.latestMu.Lock()
	for _, id := range missing {
		meta := resolved[id]
		meta.expiresAt = now.Add(latestEpisodeCacheTTL)
		c.latestEpisodeCache[id] = meta
		for _, index := range byID[id] {
			applyLatestEpisode(&results[index], meta)
		}
	}
	c.latestMu.Unlock()
	return nil
}

func applyLatestEpisode(result *PodcastResult, meta cachedLatestEpisode) {
	if result.FeedURL == "" {
		result.FeedURL = meta.feedURL
	}
	result.LatestDurationMS = meta.durationMS
	result.LatestPublishedAt = meta.publishedAt
	if result.Explicit == nil {
		result.Explicit = meta.explicit
	}
}

func parseExplicitness(values ...string) *bool {
	knownClean := false
	for _, value := range values {
		switch strings.ToLower(strings.TrimSpace(value)) {
		case "explicit":
			result := true
			return &result
		case "clean", "cleaned", "notexplicit", "not_explicit":
			knownClean = true
		}
	}
	if knownClean {
		result := false
		return &result
	}
	return nil
}

// FetchTopPodcasts returns the current overall top trending podcasts (US chart).
// Kept for backward compatibility; delegates to FetchTopChart.
func (c *ITunesClient) FetchTopPodcasts(limit int) ([]PodcastResult, error) {
	return c.FetchTopChart("us", 0, limit)
}

// FetchTopChart returns the iTunes top-podcasts chart for a storefront region,
// optionally scoped to a genre id (0 = overall). limit is clamped to iTunes'
// supported 1..200.
func (c *ITunesClient) FetchTopChart(region string, genreID, limit int) ([]PodcastResult, error) {
	if limit <= 0 || limit > 200 {
		limit = 60
	}
	region = sanitizeRegion(region)
	genreSegment := ""
	if genreID > 0 {
		genreSegment = fmt.Sprintf("/genre=%d", genreID)
	}
	reqURL := fmt.Sprintf("https://itunes.apple.com/%s/rss/toppodcasts/limit=%d%s/json", region, limit, genreSegment)

	resp, err := c.httpClient.Get(reqURL)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch iTunes top podcasts: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("iTunes API HTTP error status %d", resp.StatusCode)
	}

	var rssResp struct {
		Feed struct {
			Entry []struct {
				ID struct {
					Attributes struct {
						ID string `json:"im:id"`
					} `json:"attributes"`
				} `json:"id"`
				Title struct {
					Label string `json:"label"`
				} `json:"im:name"`
				Artist struct {
					Label string `json:"label"`
				} `json:"im:artist"`
				Summary struct {
					Label string `json:"label"`
				} `json:"summary"`
				Images []struct {
					Label string `json:"label"`
				} `json:"im:image"`
				Category struct {
					Attributes struct {
						Label string `json:"label"`
					} `json:"attributes"`
				} `json:"category"`
			} `json:"entry"`
		} `json:"feed"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&rssResp); err != nil {
		return nil, fmt.Errorf("failed to decode iTunes top podcasts JSON: %w", err)
	}

	results := make([]PodcastResult, 0, len(rssResp.Feed.Entry))
	for _, entry := range rssResp.Feed.Entry {
		artURL := ""
		if len(entry.Images) > 0 {
			artURL = entry.Images[len(entry.Images)-1].Label // Highest resolution artwork
		}

		cat := entry.Category.Attributes.Label
		var cats []string
		if cat != "" {
			cats = []string{cat}
		}
		results = append(results, PodcastResult{
			ID:          entry.ID.Attributes.ID,
			Title:       entry.Title.Label,
			Author:      entry.Artist.Label,
			FeedURL:     "", // Will be resolved on demand or via lookup
			ArtworkURL:  artURL,
			Category:    cat,
			Categories:  cats,
			Description: entry.Summary.Label,
			Language:    lang.Detect(entry.Title.Label + " " + entry.Summary.Label),
		})
	}

	return results, nil
}

// LookupFeedURL resolves an iTunes collection/track ID to its RSS feed URL via
// the iTunes Lookup API. Used to turn a Top Charts entry (which carries no feed
// URL) into an ingestible feed on demand.
func (c *ITunesClient) LookupFeedURL(id string) (string, error) {
	reqURL := fmt.Sprintf("https://itunes.apple.com/lookup?id=%s&entity=podcast", url.QueryEscape(id))

	resp, err := c.httpClient.Get(reqURL)
	if err != nil {
		return "", fmt.Errorf("iTunes lookup failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("iTunes lookup HTTP error status %d", resp.StatusCode)
	}

	var lookupResp struct {
		Results []struct {
			FeedURL string `json:"feedUrl"`
		} `json:"results"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&lookupResp); err != nil {
		return "", fmt.Errorf("failed to decode iTunes lookup JSON: %w", err)
	}

	for _, r := range lookupResp.Results {
		if r.FeedURL != "" {
			return r.FeedURL, nil
		}
	}
	return "", fmt.Errorf("no feed URL found for iTunes ID %s", id)
}

// SearchPodcasts queries iTunes Search API for podcasts matching query term using default "us" country storefront
func (c *ITunesClient) SearchPodcasts(query string, limit int) ([]PodcastResult, error) {
	return c.SearchPodcastsWithCountryExplicit(query, "us", limit, true)
}

// SearchPodcastsWithCountry queries iTunes Search API for podcasts matching query term in a specific country storefront
func (c *ITunesClient) SearchPodcastsWithCountry(query, country string, limit int) ([]PodcastResult, error) {
	return c.SearchPodcastsWithCountryExplicit(query, country, limit, true)
}

func (c *ITunesClient) SearchPodcastsWithCountryExplicit(
	query, country string,
	limit int,
	includeExplicit bool,
) ([]PodcastResult, error) {
	if limit <= 0 || limit > 50 {
		limit = 50
	}
	country = sanitizeRegion(country)
	reqURL := fmt.Sprintf("https://itunes.apple.com/search?media=podcast&entity=podcast&term=%s&country=%s&limit=%d", url.QueryEscape(query), country, limit)
	if !includeExplicit {
		reqURL += "&explicit=No"
	}

	resp, err := c.httpClient.Get(reqURL)
	if err != nil {
		return nil, fmt.Errorf("failed to search iTunes podcasts: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("iTunes Search API HTTP error status %d", resp.StatusCode)
	}

	var searchResp struct {
		ResultCount int `json:"resultCount"`
		Results     []struct {
			TrackID                int64  `json:"trackId"`
			TrackName              string `json:"trackName"`
			ArtistName             string `json:"artistName"`
			FeedURL                string `json:"feedUrl"`
			ArtworkUrl600          string `json:"artworkUrl600"`
			ArtworkUrl100          string `json:"artworkUrl100"`
			PrimaryGenre           string `json:"primaryGenreName"`
			CollectionExplicitness string `json:"collectionExplicitness"`
			TrackExplicitness      string `json:"trackExplicitness"`
			ContentAdvisoryRating  string `json:"contentAdvisoryRating"`
		} `json:"results"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&searchResp); err != nil {
		return nil, fmt.Errorf("failed to decode iTunes search JSON: %w", err)
	}

	results := make([]PodcastResult, 0, len(searchResp.Results))
	for _, item := range searchResp.Results {
		if item.FeedURL == "" {
			continue // Skip items without RSS feeds
		}
		art := item.ArtworkUrl600
		if art == "" {
			art = item.ArtworkUrl100
		}

		var cats []string
		if item.PrimaryGenre != "" {
			cats = []string{item.PrimaryGenre}
		}
		results = append(results, PodcastResult{
			ID:         fmt.Sprintf("%d", item.TrackID),
			Title:      item.TrackName,
			Author:     item.ArtistName,
			FeedURL:    item.FeedURL,
			ArtworkURL: art,
			Category:   item.PrimaryGenre,
			Categories: cats,
			Language:   lang.Detect(item.TrackName + " " + item.ArtistName),
			Explicit: parseExplicitness(
				item.CollectionExplicitness,
				item.TrackExplicitness,
				item.ContentAdvisoryRating,
			),
		})
	}
	if !includeExplicit {
		results = filterExplicit(results)
	}

	return results, nil
}

func filterExplicit(results []PodcastResult) []PodcastResult {
	filtered := results[:0]
	for _, result := range results {
		if result.Explicit != nil && *result.Explicit {
			continue
		}
		filtered = append(filtered, result)
	}
	return filtered
}

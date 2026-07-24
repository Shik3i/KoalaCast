package itunes

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

type ITunesClient struct {
	httpClient *http.Client
}

type PodcastResult struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Author      string `json:"author"`
	FeedURL     string `json:"feed_url"`
	ArtworkURL  string `json:"artwork_url"`
	Category    string `json:"category"`
	Description string `json:"description"`
}

func NewITunesClient() *ITunesClient {
	return &ITunesClient{
		httpClient: rss.NewSafeHTTPClient(rss.SafeTransportConfig{ConnectTimeout: 10 * time.Second}),
	}
}

// FetchTopPodcasts returns the current top trending podcasts from iTunes Top Charts
func (c *ITunesClient) FetchTopPodcasts(limit int) ([]PodcastResult, error) {
	if limit <= 0 || limit > 100 {
		limit = 60
	}
	reqURL := fmt.Sprintf("https://itunes.apple.com/us/rss/toppodcasts/limit=%d/json", limit)

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

		results = append(results, PodcastResult{
			ID:          entry.ID.Attributes.ID,
			Title:       entry.Title.Label,
			Author:      entry.Artist.Label,
			FeedURL:     "", // Will be resolved on demand or via lookup
			ArtworkURL:  artURL,
			Category:    entry.Category.Attributes.Label,
			Description: entry.Summary.Label,
		})
	}

	return results, nil
}

// SearchPodcasts queries iTunes Search API for podcasts matching query term
func (c *ITunesClient) SearchPodcasts(query string, limit int) ([]PodcastResult, error) {
	if limit <= 0 || limit > 50 {
		limit = 50
	}
	reqURL := fmt.Sprintf("https://itunes.apple.com/search?media=podcast&entity=podcast&term=%s&limit=%d", url.QueryEscape(query), limit)

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
			TrackID       int64  `json:"trackId"`
			TrackName     string `json:"trackName"`
			ArtistName    string `json:"artistName"`
			FeedURL       string `json:"feedUrl"`
			ArtworkUrl600 string `json:"artworkUrl600"`
			ArtworkUrl100 string `json:"artworkUrl100"`
			PrimaryGenre  string `json:"primaryGenreName"`
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

		results = append(results, PodcastResult{
			ID:         fmt.Sprintf("%d", item.TrackID),
			Title:      item.TrackName,
			Author:     item.ArtistName,
			FeedURL:    item.FeedURL,
			ArtworkURL: art,
			Category:   item.PrimaryGenre,
		})
	}

	return results, nil
}

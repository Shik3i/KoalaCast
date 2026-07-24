package podcastindex

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

type Client struct {
	apiKey     string
	apiSecret  string
	baseURL    string
	httpClient *http.Client
}

type SearchResult struct {
	ID          int64  `json:"id"`
	Title       string `json:"title"`
	URL         string `json:"url"`
	OriginalURL string `json:"originalUrl"`
	Description string `json:"description"`
	Author      string            `json:"author"`
	Artwork     string            `json:"artwork"`
	Image       string            `json:"image"`
	Categories  map[string]string `json:"categories"`
	Explicit    bool              `json:"explicit"`
}

// CategoryList returns the podcast's category names as a slice.
func (r SearchResult) CategoryList() []string {
	if len(r.Categories) == 0 {
		return nil
	}
	out := make([]string, 0, len(r.Categories))
	for _, v := range r.Categories {
		if v != "" {
			out = append(out, v)
		}
	}
	return out
}

type SearchResponse struct {
	Status      string         `json:"status"`
	Results     []SearchResult `json:"feeds"`
	Count       int            `json:"count"`
	Description string         `json:"description"`
}

// Trending returns currently-trending podcasts, optionally scoped to a category
// name (Podcast Index accepts human category names like "Technology", "News").
func (c *Client) Trending(category string, max int) ([]SearchResult, error) {
	if !c.IsConfigured() {
		return nil, fmt.Errorf("podcast index API credentials not configured")
	}
	if max <= 0 || max > 100 {
		max = 60
	}
	endpoint := fmt.Sprintf("%s/podcasts/trending?max=%d", c.baseURL, max)
	if category != "" {
		endpoint += "&cat=" + url.QueryEscape(category)
	}
	body, err := c.doAuthed(endpoint)
	if err != nil {
		return nil, err
	}
	var resp SearchResponse
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, fmt.Errorf("failed to decode trending response: %w", err)
	}
	return resp.Results, nil
}

func NewClient(apiKey, apiSecret string) *Client {
	return &Client{
		apiKey:    apiKey,
		apiSecret: apiSecret,
		baseURL:   "https://api.podcastindex.org/api/1.0",
		httpClient: rss.NewSafeHTTPClient(rss.SafeTransportConfig{
			ConnectTimeout: 10 * time.Second,
		}),
	}
}

func (c *Client) SetBaseURL(url string) {
	c.baseURL = url
}

func (c *Client) SetHTTPClient(client *http.Client) {
	c.httpClient = client
}

func (c *Client) IsConfigured() bool {
	return c.apiKey != "" && c.apiSecret != ""
}

func (c *Client) Search(query string) ([]SearchResult, error) {
	if !c.IsConfigured() {
		return nil, fmt.Errorf("podcast index API credentials not configured")
	}
	endpoint := fmt.Sprintf("%s/search/byterm?q=%s", c.baseURL, url.QueryEscape(query))
	body, err := c.doAuthed(endpoint)
	if err != nil {
		return nil, err
	}
	var searchResp SearchResponse
	if err := json.Unmarshal(body, &searchResp); err != nil {
		return nil, fmt.Errorf("failed to decode search response: %w", err)
	}
	return searchResp.Results, nil
}

// doAuthed performs a GET against a Podcast Index endpoint with the required
// signed auth headers and returns the response body.
func (c *Client) doAuthed(endpoint string) ([]byte, error) {
	req, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	epoch := time.Now().Unix()
	authHeader := fmt.Sprintf("%s%s%d", c.apiKey, c.apiSecret, epoch)
	hash := sha1.Sum([]byte(authHeader))
	hashHex := hex.EncodeToString(hash[:])

	req.Header.Set("User-Agent", "KoalaCast/1.0")
	req.Header.Set("X-Auth-Date", strconv.FormatInt(epoch, 10))
	req.Header.Set("X-Auth-Key", c.apiKey)
	req.Header.Set("Authorization", hashHex)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("podcast index request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("podcast index API returned status %d", resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

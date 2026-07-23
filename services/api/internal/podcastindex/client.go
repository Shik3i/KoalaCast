package podcastindex

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

type Client struct {
	apiKey    string
	apiSecret string
	baseURL   string
	httpClient *http.Client
}

type SearchResult struct {
	ID          int64  `json:"id"`
	Title       string `json:"title"`
	URL         string `json:"url"`
	OriginalURL string `json:"originalUrl"`
	Description string `json:"description"`
	Author      string `json:"author"`
	Artwork     string `json:"artwork"`
	Explicit    bool   `json:"explicit"`
}

type SearchResponse struct {
	Status      string         `json:"status"`
	Results     []SearchResult `json:"feeds"`
	Count       int            `json:"count"`
	Description string         `json:"description"`
}

func NewClient(apiKey, apiSecret string) *Client {
	return &Client{
		apiKey:     apiKey,
		apiSecret:  apiSecret,
		baseURL:    "https://api.podcastindex.org/api/1.0",
		httpClient: rss.SafeHTTPClient(10 * time.Second),
	}
}

func (c *Client) IsConfigured() bool {
	return c.apiKey != "" && c.apiSecret != ""
}

func (c *Client) Search(query string) ([]SearchResult, error) {
	if !c.IsConfigured() {
		return nil, fmt.Errorf("podcast index API credentials not configured")
	}

	endpoint := fmt.Sprintf("%s/search/byterm?q=%s", c.baseURL, url.QueryEscape(query))
	req, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	// Podcast Index Auth Headers
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

	var searchResp SearchResponse
	if err := json.NewDecoder(resp.Body).Decode(&searchResp); err != nil {
		return nil, fmt.Errorf("failed to decode search response: %w", err)
	}

	return searchResp.Results, nil
}

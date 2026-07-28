package handlers

import (
	"bytes"
	"container/list"
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
	"golang.org/x/sync/singleflight"
)

type lruItem struct {
	key  string
	val  []byte
	size int64
}

type MemoryLRUCache struct {
	mu        sync.Mutex
	items     map[string]*list.Element
	evictList *list.List
	maxBytes  int64
	curBytes  int64
}

func NewMemoryLRUCache(maxBytes int64) *MemoryLRUCache {
	return &MemoryLRUCache{
		items:     make(map[string]*list.Element),
		evictList: list.New(),
		maxBytes:  maxBytes,
	}
}

func (c *MemoryLRUCache) Get(key string) ([]byte, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, ok := c.items[key]; ok {
		c.evictList.MoveToFront(elem)
		return elem.Value.(*lruItem).val, true
	}
	return nil, false
}

func (c *MemoryLRUCache) Put(key string, data []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()

	dataLen := int64(len(data))
	if dataLen > c.maxBytes {
		return
	}

	if elem, ok := c.items[key]; ok {
		c.evictList.MoveToFront(elem)
		item := elem.Value.(*lruItem)
		c.curBytes += dataLen - item.size
		item.size = dataLen
		item.val = data
	} else {
		item := &lruItem{key: key, val: data, size: dataLen}
		elem := c.evictList.PushFront(item)
		c.items[key] = elem
		c.curBytes += dataLen
	}

	for c.curBytes > c.maxBytes {
		oldest := c.evictList.Back()
		if oldest == nil {
			break
		}
		c.evictList.Remove(oldest)
		item := oldest.Value.(*lruItem)
		delete(c.items, item.key)
		c.curBytes -= item.size
	}
}

type ProxyHandler struct {
	httpClient   *http.Client
	memCache     *MemoryLRUCache
	requestGroup singleflight.Group
}

func NewProxyHandler() *ProxyHandler {
	return &ProxyHandler{
		// SSRF-safe client: never allow loopback/private/link-local targets. These
		// endpoints fetch fully attacker-controlled URLs, so AllowLoopback MUST stay
		// false (it exists only for unit tests targeting httptest.NewServer).
		httpClient: rss.NewSafeHTTPClient(rss.SafeTransportConfig{
			ConnectTimeout: 10 * time.Second,
		}),
		// Bounded 100 MB In-Memory RAM LRU cache
		memCache: NewMemoryLRUCache(100 * 1024 * 1024),
	}
}

// maxDecodedPixels caps the pixel count of a source image before it is decoded
// into an uncompressed bitmap. Without this a tiny, highly compressed file (a
// "decompression bomb") could decode to gigabytes of RGBA and OOM the process.
// 40 MP comfortably covers legitimate podcast artwork (typically <=3000x3000).
const maxDecodedPixels = 40 * 1000 * 1000
const imageProxyTimeout = 1200 * time.Millisecond
const maxAudioDownloadBytes = int64(2 * 1024 * 1024 * 1024)

//go:embed assets/cover-placeholder.webp
var imageFallbackWebP []byte

func writeImageFallback(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "image/webp")
	w.Header().Set("Content-Length", strconv.Itoa(len(imageFallbackWebP)))
	// This is a transient upstream failure, not the requested artwork. Browsers
	// and the service worker must retry rather than pinning the placeholder.
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-KoalaCast-Image-Fallback", "true")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(imageFallbackWebP)
}

// GetAudioProxy streams an enclosure through the listener's KoalaCast instance.
// It exists for explicit PWA downloads only; normal playback still contacts the
// publisher directly and therefore does not spend server bandwidth.
func (h *ProxyHandler) GetAudioProxy(w http.ResponseWriter, r *http.Request) {
	rawURL := strings.TrimSpace(r.URL.Query().Get("url"))
	if rawURL == "" || (!strings.HasPrefix(rawURL, "http://") && !strings.HasPrefix(rawURL, "https://")) {
		http.Error(w, `{"error":"valid http/https url required"}`, http.StatusBadRequest)
		return
	}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, rawURL, nil)
	if err != nil {
		http.Error(w, `{"error":"invalid url"}`, http.StatusBadRequest)
		return
	}
	req.Header.Set("User-Agent", "KoalaCast/1.0 Podcast Player")
	req.Header.Set("Accept", "audio/*,application/octet-stream;q=0.8")
	if rangeHeader := r.Header.Get("Range"); rangeHeader != "" {
		req.Header.Set("Range", rangeHeader)
	}

	resp, err := h.httpClient.Do(req)
	if err != nil {
		http.Error(w, `{"error":"audio upstream unavailable"}`, http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		http.Error(w, `{"error":"audio upstream rejected request"}`, http.StatusBadGateway)
		return
	}
	if resp.ContentLength > maxAudioDownloadBytes {
		http.Error(w, `{"error":"audio file exceeds 2 GiB limit"}`, http.StatusRequestEntityTooLarge)
		return
	}
	if resp.ContentLength < 0 && r.Header.Get("Range") == "" {
		http.Error(w, `{"error":"audio size is unknown"}`, http.StatusBadGateway)
		return
	}

	for _, header := range []string{
		"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified",
	} {
		if value := resp.Header.Get(header); value != "" {
			w.Header().Set(header, value)
		}
	}
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(resp.StatusCode)
	if r.Method != http.MethodHead {
		_, _ = io.Copy(w, io.LimitReader(resp.Body, maxAudioDownloadBytes+1))
	}
}

// GetImageProxy fetches an external image, resizes it, converts/compresses it to optimized JPEG,
// and caches it entirely in RAM (In-Memory) to guarantee zero disk I/O, privacy, and maximum performance.
// Uses singleflight to coalesce duplicate concurrent requests (Thundering Herd protection).
func (h *ProxyHandler) GetImageProxy(w http.ResponseWriter, r *http.Request) {
	rawURL := strings.TrimSpace(r.URL.Query().Get("url"))
	if rawURL == "" || (!strings.HasPrefix(rawURL, "http://") && !strings.HasPrefix(rawURL, "https://")) {
		http.Error(w, `{"error":"valid http/https url required"}`, http.StatusBadRequest)
		return
	}

	targetW := 300
	if wStr := r.URL.Query().Get("w"); wStr != "" {
		if parsedW, err := strconv.Atoi(wStr); err == nil && parsedW > 0 && parsedW <= 1200 {
			targetW = parsedW
		}
	}

	hHasher := sha256.New()
	hHasher.Write([]byte(rawURL + "_" + strconv.Itoa(targetW)))
	cacheKey := hex.EncodeToString(hHasher.Sum(nil))

	// 1. Fast path: Check In-Memory RAM LRU Cache (0 ms, 0 Disk I/O)
	if cachedData, ok := h.memCache.Get(cacheKey); ok {
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Content-Length", strconv.Itoa(len(cachedData)))
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(cachedData)
		return
	}

	// 2. Coalesce duplicate concurrent image processing using singleflight
	res, err, _ := h.requestGroup.Do(cacheKey, func() (interface{}, error) {
		// Double-check cache in case another goroutine completed it
		if cachedData, ok := h.memCache.Get(cacheKey); ok {
			return cachedData, nil
		}

		ctx, cancel := context.WithTimeout(r.Context(), imageProxyTimeout)
		defer cancel()
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
		if err != nil {
			return nil, fmt.Errorf("invalid url")
		}
		req.Header.Set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
		req.Header.Set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

		resp, err := h.httpClient.Do(req)
		if err != nil {
			return nil, fmt.Errorf("failed to fetch remote image")
		}
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("remote image non-200")
		}

		// Read the (byte-bounded) body into memory once so the image header can
		// be inspected before committing to a full decode.
		rawBytes, err := io.ReadAll(io.LimitReader(resp.Body, 15*1024*1024))
		if err != nil {
			return nil, fmt.Errorf("failed to read remote image")
		}

		// Reject decompression bombs: check declared dimensions (header-only,
		// cheap) before decoding the full bitmap into RAM.
		if cfg, _, cfgErr := image.DecodeConfig(bytes.NewReader(rawBytes)); cfgErr == nil {
			if int64(cfg.Width)*int64(cfg.Height) > maxDecodedPixels {
				return nil, fmt.Errorf("image dimensions too large")
			}
		}

		img, _, err := image.Decode(bytes.NewReader(rawBytes))
		if err != nil {
			return nil, fmt.Errorf("failed to decode image")
		}

		bounds := img.Bounds()
		srcW := bounds.Dx()
		srcH := bounds.Dy()
		if srcW == 0 || srcH == 0 {
			return nil, fmt.Errorf("invalid image dimensions")
		}

		dstW := targetW
		dstH := (srcH * dstW) / srcW
		if dstH < 1 {
			dstH = 1
		}

		dst := image.NewRGBA(image.Rect(0, 0, dstW, dstH))
		draw.CatmullRom.Scale(dst, dst.Bounds(), img, bounds, draw.Over, nil)

		var buf bytes.Buffer
		if err := jpeg.Encode(&buf, dst, &jpeg.Options{Quality: 82}); err != nil {
			return nil, fmt.Errorf("encode failed")
		}

		imgBytes := buf.Bytes()
		h.memCache.Put(cacheKey, imgBytes)
		return imgBytes, nil
	})

	if err != nil {
		writeImageFallback(w)
		return
	}

	imgBytes, ok := res.([]byte)
	if !ok {
		http.Error(w, `{"error":"internal error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Content-Length", strconv.Itoa(len(imgBytes)))
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(imgBytes)
}

type ChapterItem struct {
	StartTime float64 `json:"startTime"`
	Title     string  `json:"title"`
	Img       string  `json:"img,omitempty"`
	URL       string  `json:"url,omitempty"`
}

func (h *ProxyHandler) GetChapters(w http.ResponseWriter, r *http.Request) {
	url := strings.TrimSpace(r.URL.Query().Get("url"))
	if url == "" || (!strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://")) {
		http.Error(w, `{"error":"valid http/https url parameter required"}`, http.StatusBadRequest)
		return
	}

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		http.Error(w, `{"error":"invalid url"}`, http.StatusBadRequest)
		return
	}
	req.Header.Set("User-Agent", "KoalaCast/1.0 Podcast Player")
	req.Header.Set("Accept", "application/json, text/plain, */*")

	resp, err := h.httpClient.Do(req)
	if err != nil {
		http.Error(w, `{"error":"failed to fetch chapters json"}`, http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		http.Error(w, `{"error":"chapters endpoint returned non-200"}`, http.StatusBadGateway)
		return
	}

	limitReader := io.LimitReader(resp.Body, 2*1024*1024)
	bodyBytes, err := io.ReadAll(limitReader)
	if err != nil {
		http.Error(w, `{"error":"failed to read chapters response"}`, http.StatusBadGateway)
		return
	}

	var parsed struct {
		Chapters []ChapterItem `json:"chapters"`
	}
	if err := json.Unmarshal(bodyBytes, &parsed); err == nil && len(parsed.Chapters) > 0 {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		_ = json.NewEncoder(w).Encode(map[string]any{"chapters": parsed.Chapters})
		return
	}

	var rawArray []ChapterItem
	if err := json.Unmarshal(bodyBytes, &rawArray); err == nil && len(rawArray) > 0 {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		_ = json.NewEncoder(w).Encode(map[string]any{"chapters": rawArray})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	_, _ = w.Write(bodyBytes)
}

func (h *ProxyHandler) GetTranscript(w http.ResponseWriter, r *http.Request) {
	url := strings.TrimSpace(r.URL.Query().Get("url"))
	if url == "" || (!strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://")) {
		http.Error(w, `{"error":"valid http/https url parameter required"}`, http.StatusBadRequest)
		return
	}

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		http.Error(w, `{"error":"invalid url"}`, http.StatusBadRequest)
		return
	}
	req.Header.Set("User-Agent", "KoalaCast/1.0 Podcast Player")
	req.Header.Set("Accept", "text/vtt, text/plain, application/x-subrip, application/json, */*")

	resp, err := h.httpClient.Do(req)
	if err != nil {
		http.Error(w, `{"error":"failed to fetch transcript"}`, http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		http.Error(w, `{"error":"transcript endpoint returned non-200"}`, http.StatusBadGateway)
		return
	}

	limitReader := io.LimitReader(resp.Body, 5*1024*1024)
	bodyBytes, err := io.ReadAll(limitReader)
	if err != nil {
		http.Error(w, `{"error":"failed to read transcript response"}`, http.StatusBadGateway)
		return
	}

	contentType := resp.Header.Get("Content-Type")
	if strings.Contains(contentType, "json") || strings.HasSuffix(url, ".json") {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		_, _ = w.Write(bodyBytes)
		return
	}

	parsedCues := parseSRTOrVTT(string(bodyBytes))
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	_ = json.NewEncoder(w).Encode(map[string]any{"cues": parsedCues})
}

type CueItem struct {
	Start float64 `json:"start"`
	End   float64 `json:"end"`
	Text  string  `json:"text"`
}

func parseSRTOrVTT(content string) []CueItem {
	var cues []CueItem
	lines := strings.Split(strings.ReplaceAll(content, "\r\n", "\n"), "\n")

	timeRegexp := regexp.MustCompile(`(?:(\d{2}):)?(\d{2}):(\d{2})[\.,](\d{3})\s*-->\s*(?:(\d{2}):)?(\d{2}):(\d{2})[\.,](\d{3})`)

	var currentCue *CueItem

	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || line == "WEBVTT" || strings.HasPrefix(line, "NOTE") {
			if currentCue != nil && currentCue.Text != "" {
				cues = append(cues, *currentCue)
				currentCue = nil
			}
			continue
		}

		matches := timeRegexp.FindStringSubmatch(line)
		if len(matches) > 0 {
			if currentCue != nil && currentCue.Text != "" {
				cues = append(cues, *currentCue)
			}
			startSec := parseTimestampToSeconds(matches[1], matches[2], matches[3], matches[4])
			endSec := parseTimestampToSeconds(matches[5], matches[6], matches[7], matches[8])
			currentCue = &CueItem{Start: startSec, End: endSec, Text: ""}
			continue
		}

		if currentCue != nil {
			if currentCue.Text != "" {
				currentCue.Text += " " + line
			} else {
				currentCue.Text = line
			}
		}
	}

	if currentCue != nil && currentCue.Text != "" {
		cues = append(cues, *currentCue)
	}

	return cues
}

func parseTimestampToSeconds(hStr, mStr, sStr, msStr string) float64 {
	h, _ := strconv.Atoi(hStr)
	m, _ := strconv.Atoi(mStr)
	s, _ := strconv.Atoi(sStr)
	ms, _ := strconv.Atoi(msStr)
	return float64(h*3600+m*60+s) + float64(ms)/1000.0
}

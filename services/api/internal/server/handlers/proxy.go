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
	"math/big"
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
	httpClient        *http.Client
	streamClient      *http.Client
	memCache          *MemoryLRUCache
	requestGroup      singleflight.Group
	audioProxyEnabled bool
	audioSlots        chan struct{}
}

func NewProxyHandler(audioProxyEnabled bool) *ProxyHandler {
	return &ProxyHandler{
		// SSRF-safe client: never allow loopback/private/link-local targets. These
		// endpoints fetch fully attacker-controlled URLs, so AllowLoopback MUST stay
		// false (it exists only for unit tests targeting httptest.NewServer).
		httpClient: rss.NewSafeHTTPClient(rss.SafeTransportConfig{
			ConnectTimeout: 10 * time.Second,
		}),
		streamClient: rss.NewSafeHTTPClient(rss.SafeTransportConfig{
			ConnectTimeout:        10 * time.Second,
			ResponseTimeout:       15 * time.Second,
			DisableRequestTimeout: true,
		}),
		// Bounded 100 MB In-Memory RAM LRU cache
		memCache:          NewMemoryLRUCache(100 * 1024 * 1024),
		audioProxyEnabled: audioProxyEnabled,
		audioSlots:        make(chan struct{}, 16),
	}
}

// maxDecodedPixels caps the pixel count of a source image before it is decoded
// into an uncompressed bitmap. Without this a tiny, highly compressed file (a
// "decompression bomb") could decode to gigabytes of RGBA and OOM the process.
// 40 MP comfortably covers legitimate podcast artwork (typically <=3000x3000).
const maxDecodedPixels = 40 * 1000 * 1000

// Cold publisher/CDN connections routinely exceed one second. A 1.2 s deadline
// made the proxy return its temporary placeholder before otherwise healthy
// artwork arrived, forcing users to reload until a request happened to be fast.
// Singleflight still coalesces identical requests while this bounded deadline
// gives DNS, TLS and the first upstream response a realistic window.
// acceptedImageFormats mirrors the decoders registered by this package's
// imports (JPEG, PNG, GIF, WebP). Content negotiation is a promise about what
// the client can read; advertising a format with no decoder turns every
// negotiating CDN into a broken image.
const acceptedImageFormats = "image/webp,image/jpeg,image/png,image/gif;q=0.8,*/*;q=0.5"

const imageProxyTimeout = 8 * time.Second
const maxAudioDownloadBytes = int64(2 * 1024 * 1024 * 1024)
const maxAudioStreamDuration = 4 * time.Hour
const audioStreamIdleTimeout = 45 * time.Second

// A redirect chain plus one ranged byte. Generous enough for a cold CDN, short
// enough that the client can fall back rather than stall before playback.
const audioResolveTimeout = 8 * time.Second

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
// The browser uses it only as an explicitly enabled fallback when publisher CORS
// blocks direct downloads or Web Audio effects. Ordinary playback stays direct.
func (h *ProxyHandler) GetAudioProxy(w http.ResponseWriter, r *http.Request) {
	if !h.audioProxyEnabled {
		http.Error(w, `{"error":"audio proxy disabled"}`, http.StatusForbidden)
		return
	}
	rawURL := strings.TrimSpace(r.URL.Query().Get("url"))
	if rawURL == "" || (!strings.HasPrefix(rawURL, "http://") && !strings.HasPrefix(rawURL, "https://")) {
		http.Error(w, `{"error":"valid http/https url required"}`, http.StatusBadRequest)
		return
	}
	rangeHeader := strings.TrimSpace(r.Header.Get("Range"))
	if rangeHeader != "" && !validAudioRange(rangeHeader) {
		w.Header().Set("Content-Range", "bytes */"+strconv.FormatInt(maxAudioDownloadBytes, 10))
		http.Error(w, `{"error":"invalid or oversized byte range"}`, http.StatusRequestedRangeNotSatisfiable)
		return
	}
	select {
	case h.audioSlots <- struct{}{}:
		defer func() { <-h.audioSlots }()
	default:
		w.Header().Set("Retry-After", "1")
		http.Error(w, `{"error":"audio proxy is at capacity"}`, http.StatusServiceUnavailable)
		return
	}

	streamContext, cancel := context.WithTimeout(r.Context(), maxAudioStreamDuration)
	defer cancel()
	req, err := http.NewRequestWithContext(streamContext, http.MethodGet, rawURL, nil)
	if err != nil {
		http.Error(w, `{"error":"invalid url"}`, http.StatusBadRequest)
		return
	}
	req.Header.Set("User-Agent", "KoalaCast/1.0 Podcast Player")
	req.Header.Set("Accept", "audio/*,application/octet-stream;q=0.8")
	if rangeHeader != "" {
		req.Header.Set("Range", rangeHeader)
	}

	resp, err := h.streamClient.Do(req)
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
	if resp.StatusCode == http.StatusPartialContent {
		total, ok := audioContentRangeTotal(resp.Header.Get("Content-Range"))
		if !ok || total > maxAudioDownloadBytes {
			http.Error(w, `{"error":"invalid or oversized upstream content range"}`, http.StatusBadGateway)
			return
		}
	}
	if resp.ContentLength < 0 && rangeHeader == "" {
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
		_ = copyAudioStream(w, resp.Body, maxAudioDownloadBytes)
	}
}

var audioRangePattern = regexp.MustCompile(`^bytes=(\d*)-(\d*)$`)
var audioContentRangePattern = regexp.MustCompile(`^bytes \d+-\d+/(\d+)$`)

func validAudioRange(value string) bool {
	match := audioRangePattern.FindStringSubmatch(value)
	if match == nil || (match[1] == "" && match[2] == "") {
		return false
	}
	start, startOK := new(big.Int).SetString(match[1], 10)
	end, endOK := new(big.Int).SetString(match[2], 10)
	limit := big.NewInt(maxAudioDownloadBytes)
	if match[1] == "" {
		return endOK && end.Sign() > 0 && end.Cmp(limit) <= 0
	}
	if !startOK || start.Sign() < 0 || start.Cmp(limit) >= 0 {
		return false
	}
	if match[2] == "" {
		return true
	}
	if !endOK || end.Cmp(start) < 0 || end.Cmp(limit) >= 0 {
		return false
	}
	length := new(big.Int).Sub(end, start)
	length.Add(length, big.NewInt(1))
	return length.Cmp(limit) <= 0
}

func audioContentRangeTotal(value string) (int64, bool) {
	match := audioContentRangePattern.FindStringSubmatch(strings.TrimSpace(value))
	if match == nil {
		return 0, false
	}
	total, err := strconv.ParseInt(match[1], 10, 64)
	return total, err == nil && total > 0
}

func copyAudioStream(w http.ResponseWriter, body io.ReadCloser, limit int64) error {
	controller := http.NewResponseController(w)
	buffer := make([]byte, 256*1024)
	var written int64
	for {
		idleExpired := make(chan struct{}, 1)
		timer := time.AfterFunc(audioStreamIdleTimeout, func() {
			idleExpired <- struct{}{}
			_ = body.Close()
		})
		n, readErr := body.Read(buffer)
		timer.Stop()
		select {
		case <-idleExpired:
			return fmt.Errorf("audio upstream idle timeout")
		default:
		}
		if n > 0 {
			written += int64(n)
			if written > limit {
				return fmt.Errorf("audio stream exceeded size limit")
			}
			_ = controller.SetWriteDeadline(time.Now().Add(audioStreamIdleTimeout))
			if _, err := w.Write(buffer[:n]); err != nil {
				return err
			}
		}
		if readErr != nil {
			if readErr == io.EOF {
				return nil
			}
			return readErr
		}
	}
}

// GetAudioResolve follows an enclosure's redirect chain and reports where it ends
// up and whether that host allows cross-origin reads.
//
// Podcast enclosures are routinely published behind prefix trackers (podtrac,
// chartable, pdst.fm). Those prefixes rarely send Access-Control-Allow-Origin,
// while the CDN they redirect to usually does — so the browser gives up on a URL
// whose real host would have worked. Web Audio needs CORS for silence skipping,
// volume boost and visualisers, and there is no client-side way around that: the
// browser refuses to expose cross-origin samples, deliberately.
//
// Resolving the chain here costs one ranged byte and lets the client stream the
// audio *directly* from the final host. The listener's audio still never passes
// through this server; only the redirect lookup does.
func (h *ProxyHandler) GetAudioResolve(w http.ResponseWriter, r *http.Request) {
	rawURL := strings.TrimSpace(r.URL.Query().Get("url"))
	if rawURL == "" || (!strings.HasPrefix(rawURL, "http://") && !strings.HasPrefix(rawURL, "https://")) {
		http.Error(w, `{"error":"valid http/https url required"}`, http.StatusBadRequest)
		return
	}

	origin := strings.TrimSpace(r.URL.Query().Get("origin"))

	ctx, cancel := context.WithTimeout(r.Context(), audioResolveTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		http.Error(w, `{"error":"invalid url"}`, http.StatusBadRequest)
		return
	}
	req.Header.Set("User-Agent", "KoalaCast/1.0 Podcast Player")
	req.Header.Set("Accept", "audio/*,application/octet-stream;q=0.8")
	// One byte is enough to complete the redirect chain and read the CORS headers
	// without pulling an episode through this process.
	req.Header.Set("Range", "bytes=0-0")
	if origin != "" {
		// Ask as the browser would, so the answer reflects what the browser will
		// actually be told rather than what the CDN returns to an originless client.
		req.Header.Set("Origin", origin)
	}

	resp, err := h.httpClient.Do(req)
	if err != nil {
		http.Error(w, `{"error":"audio upstream unavailable"}`, http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1024))

	finalURL := rawURL
	if resp.Request != nil && resp.Request.URL != nil {
		finalURL = resp.Request.URL.String()
	}

	allowOrigin := resp.Header.Get("Access-Control-Allow-Origin")
	corsAllowed := allowOrigin == "*" || (origin != "" && strings.EqualFold(allowOrigin, origin))

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "private, max-age=3600")
	_ = json.NewEncoder(w).Encode(audioResolveResponse{
		URL:         finalURL,
		Redirected:  finalURL != rawURL,
		CORSAllowed: corsAllowed,
	})
}

type audioResolveResponse struct {
	URL         string `json:"url"`
	Redirected  bool   `json:"redirected"`
	CORSAllowed bool   `json:"cors_allowed"`
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
		// Ask only for what this process can actually decode. Copying a browser's
		// Accept header meant advertising AVIF, and every CDN that negotiates on
		// it — imgix, Cloudinary, Cloudflare Images, anything with `auto=format` —
		// duly returned AVIF. Go has no AVIF decoder here, so the decode below
		// failed and the handler answered with its own placeholder, at 200, for
		// artwork that was never broken. SVG is excluded for the same reason it
		// always was: it is not a raster format this resizer can handle.
		req.Header.Set("Accept", acceptedImageFormats)

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
		dstH64 := (int64(srcH) * int64(dstW)) / int64(srcW)
		if dstH64 < 1 {
			dstH64 = 1
		}
		if int64(dstW)*dstH64 > maxDecodedPixels || dstH64 > int64(^uint(0)>>1) {
			return nil, fmt.Errorf("resized image dimensions too large")
		}
		dstH := int(dstH64)

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

	bodyBytes, err := readLimitedBody(resp.Body, 2*1024*1024)
	if err != nil {
		http.Error(w, `{"error":"chapters response exceeds 2 MiB limit"}`, http.StatusRequestEntityTooLarge)
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

	bodyBytes, err := readLimitedBody(resp.Body, 5*1024*1024)
	if err != nil {
		http.Error(w, `{"error":"transcript response exceeds 5 MiB limit"}`, http.StatusRequestEntityTooLarge)
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

func readLimitedBody(reader io.Reader, limit int64) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(reader, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > limit {
		return nil, fmt.Errorf("response exceeds %d byte limit", limit)
	}
	return body, nil
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

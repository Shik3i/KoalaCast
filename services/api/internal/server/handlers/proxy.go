package handlers

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"image"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

type ProxyHandler struct {
	httpClient *http.Client
}

func NewProxyHandler() *ProxyHandler {
	return &ProxyHandler{
		httpClient: rss.NewSafeHTTPClient(rss.SafeTransportConfig{
			ConnectTimeout: 10 * time.Second,
			AllowLoopback:  true,
		}),
	}
}

// GetImageProxy fetches an external image, resizes it, converts/compresses it to optimized JPEG,
// and caches it permanently on disk to guarantee 100% privacy (no direct client requests to 3rd parties)
// and high performance.
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

	cacheDir := os.Getenv("IMAGE_CACHE_DIR")
	if cacheDir == "" {
		cacheDir = "/tmp/koalacast_img_cache"
	}
	_ = os.MkdirAll(cacheDir, 0755)

	cacheFile := filepath.Join(cacheDir, cacheKey+".jpg")

	if info, err := os.Stat(cacheFile); err == nil && info.Size() > 0 {
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		http.ServeFile(w, r, cacheFile)
		return
	}

	req, err := http.NewRequest(http.MethodGet, rawURL, nil)
	if err != nil {
		http.Error(w, `{"error":"invalid url"}`, http.StatusBadRequest)
		return
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
	req.Header.Set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

	resp, err := h.httpClient.Do(req)
	if err != nil {
		http.Error(w, `{"error":"failed to fetch remote image"}`, http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		http.Error(w, `{"error":"remote image non-200"}`, http.StatusBadGateway)
		return
	}

	limitReader := io.LimitReader(resp.Body, 15*1024*1024)
	img, _, err := image.Decode(limitReader)
	if err != nil {
		http.Error(w, `{"error":"failed to decode image"}`, http.StatusUnprocessableEntity)
		return
	}

	bounds := img.Bounds()
	srcW := bounds.Dx()
	srcH := bounds.Dy()
	if srcW == 0 || srcH == 0 {
		http.Error(w, `{"error":"invalid image dimensions"}`, http.StatusUnprocessableEntity)
		return
	}

	dstW := targetW
	dstH := (srcH * dstW) / srcW
	if dstH < 1 {
		dstH = 1
	}

	dst := image.NewRGBA(image.Rect(0, 0, dstW, dstH))
	draw.CatmullRom.Scale(dst, dst.Bounds(), img, bounds, draw.Over, nil)

	tmpFile := cacheFile + ".tmp"
	f, err := os.Create(tmpFile)
	if err != nil {
		http.Error(w, `{"error":"cache write failed"}`, http.StatusInternalServerError)
		return
	}

	if err := jpeg.Encode(f, dst, &jpeg.Options{Quality: 82}); err != nil {
		f.Close()
		_ = os.Remove(tmpFile)
		http.Error(w, `{"error":"encode failed"}`, http.StatusInternalServerError)
		return
	}
	f.Close()

	_ = os.Rename(tmpFile, cacheFile)

	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	http.ServeFile(w, r, cacheFile)
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
	req.Header.Set("User-Agent", "KoalaCast/1.0")

	resp, err := h.httpClient.Do(req)
	if err != nil {
		http.Error(w, `{"error":"failed to fetch chapters"}`, http.StatusInternalServerError)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		http.Error(w, `{"error":"remote server returned non-200"}`, http.StatusBadGateway)
		return
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 5*1024*1024))
	if err != nil {
		http.Error(w, `{"error":"failed to read chapter content"}`, http.StatusInternalServerError)
		return
	}

	var raw struct {
		Chapters []ChapterItem `json:"chapters"`
	}

	if err := json.Unmarshal(body, &raw); err != nil {
		// Fallback: try decoding array directly
		var arr []ChapterItem
		if errArr := json.Unmarshal(body, &arr); errArr == nil {
			raw.Chapters = arr
		} else {
			http.Error(w, `{"error":"failed to parse JSON chapters"}`, http.StatusUnprocessableEntity)
			return
		}
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"chapters": raw.Chapters,
	})
}

type TranscriptCue struct {
	Start float64 `json:"start"`
	End   float64 `json:"end"`
	Text  string  `json:"text"`
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
	req.Header.Set("User-Agent", "KoalaCast/1.0")

	resp, err := h.httpClient.Do(req)
	if err != nil {
		http.Error(w, `{"error":"failed to fetch transcript"}`, http.StatusInternalServerError)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		http.Error(w, `{"error":"remote server returned non-200"}`, http.StatusBadGateway)
		return
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 5*1024*1024))
	if err != nil {
		http.Error(w, `{"error":"failed to read transcript content"}`, http.StatusInternalServerError)
		return
	}

	cues := parseTranscriptBody(string(body))

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"cues": cues,
	})
}

var timestampRegex = regexp.MustCompile(`(?:(\d+):)?(\d{2}):(\d{2})[\.,](\d{3})`)

func parseTimestampToSeconds(s string) float64 {
	m := timestampRegex.FindStringSubmatch(s)
	if len(m) < 5 {
		return 0
	}
	h, _ := strconv.Atoi(m[1])
	min, _ := strconv.Atoi(m[2])
	sec, _ := strconv.Atoi(m[3])
	ms, _ := strconv.Atoi(m[4])

	return float64(h*3600+min*60+sec) + float64(ms)/1000.0
}

func parseTranscriptBody(content string) []TranscriptCue {
	// First try JSON format (Podcast Index JSON transcript format)
	var jsonDoc struct {
		Segments []struct {
			StartTime float64 `json:"startTime"`
			EndTime   float64 `json:"endTime"`
			Body      string  `json:"body"`
		} `json:"segments"`
	}

	if err := json.Unmarshal([]byte(content), &jsonDoc); err == nil && len(jsonDoc.Segments) > 0 {
		cues := make([]TranscriptCue, 0, len(jsonDoc.Segments))
		for _, s := range jsonDoc.Segments {
			cues = append(cues, TranscriptCue{
				Start: s.StartTime,
				End:   s.EndTime,
				Text:  strings.TrimSpace(s.Body),
			})
		}
		return cues
	}

	// Fallback to WebVTT / SRT parser
	lines := strings.Split(strings.ReplaceAll(content, "\r\n", "\n"), "\n")
	var cues []TranscriptCue
	var currentCue *TranscriptCue

	for i := 0; i < len(lines); i++ {
		line := strings.TrimSpace(lines[i])
		if line == "" || line == "WEBVTT" || strings.HasPrefix(line, "NOTE") {
			if currentCue != nil && currentCue.Text != "" {
				cues = append(cues, *currentCue)
				currentCue = nil
			}
			continue
		}

		if strings.Contains(line, "-->") {
			parts := strings.Split(line, "-->")
			if len(parts) == 2 {
				if currentCue != nil && currentCue.Text != "" {
					cues = append(cues, *currentCue)
				}
				startSec := parseTimestampToSeconds(strings.TrimSpace(parts[0]))
				endSec := parseTimestampToSeconds(strings.TrimSpace(parts[1]))
				currentCue = &TranscriptCue{
					Start: startSec,
					End:   endSec,
					Text:  "",
				}
			}
			continue
		}

		if currentCue != nil {
			// Strip HTML / formatting tags like <v Speaker>
			cleanLine := regexp.MustCompile(`<[^>]*>`).ReplaceAllString(line, "")
			cleanLine = strings.TrimSpace(cleanLine)
			if cleanLine != "" {
				if currentCue.Text != "" {
					currentCue.Text += " " + cleanLine
				} else {
					currentCue.Text = cleanLine
				}
			}
		}
	}

	if currentCue != nil && currentCue.Text != "" {
		cues = append(cues, *currentCue)
	}

	return cues
}

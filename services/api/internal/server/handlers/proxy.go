package handlers

import (
	"encoding/json"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
)

type ProxyHandler struct {
	httpClient *http.Client
}

func NewProxyHandler() *ProxyHandler {
	return &ProxyHandler{
		httpClient: rss.NewSafeHTTPClient(rss.SafeTransportConfig{
			ConnectTimeout: 10 * time.Second,
		}),
	}
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

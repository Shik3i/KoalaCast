package handlers

import (
	"encoding/json"
	"net/http"
	"sort"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
)

type GlobalStatsHandler struct {
	DB *db.DB
}

const maxListeningSessionSpanMS = int64((7 * 24 * time.Hour) / time.Millisecond)

type globalTimeTotal struct {
	Label string `json:"label"`
	MS    int64  `json:"ms"`
}

type globalPodcastTotal struct {
	Rank     int    `json:"rank"`
	ID       string `json:"id"`
	Title    string `json:"title"`
	MS       int64  `json:"ms"`
	Episodes int    `json:"episodes"`
}

type globalListenerTotal struct {
	Rank       int    `json:"rank"`
	Username   string `json:"username"`
	MS         int64  `json:"ms"`
	ActiveDays int    `json:"active_days"`
	Podcasts   int    `json:"podcasts"`
}

type globalDayTotal struct {
	Date string `json:"date"`
	MS   int64  `json:"ms"`
}

type globalPodcastAccumulator struct {
	ID       string
	Title    string
	MS       int64
	Episodes map[string]struct{}
}

type globalListenerAccumulator struct {
	Username string
	MS       int64
	Days     map[string]struct{}
	Podcasts map[string]struct{}
}

func (h *GlobalStatsHandler) GetPreference(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	var enabled int
	var enabledAt int64
	err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT global_stats_opt_in, global_stats_opt_in_at
		FROM users WHERE id = ?
	`, authUser.ID).Scan(&enabled, &enabledAt)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"global_stats_opt_in":    enabled == 1,
		"global_stats_opt_in_at": enabledAt,
	})
}

func (h *GlobalStatsHandler) UpdatePreference(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	var body struct {
		GlobalStatsOptIn *bool `json:"global_stats_opt_in"`
	}
	if err := decodeLimitedJSONStrict(w, r, 4096, &body); err != nil || body.GlobalStatsOptIn == nil {
		http.Error(w, `{"error":"global_stats_opt_in must be a boolean"}`, http.StatusBadRequest)
		return
	}

	enabled := 0
	enabledAt := int64(0)
	if *body.GlobalStatsOptIn {
		enabled = 1
		enabledAt = time.Now().UnixMilli()
	}
	result, err := h.DB.SQL.ExecContext(r.Context(), `
		UPDATE users
		SET global_stats_opt_in = ?, global_stats_opt_in_at = ?, updated_at = ?
		WHERE id = ?
	`, enabled, enabledAt, time.Now().UnixMilli(), authUser.ID)
	if err != nil {
		http.Error(w, `{"error":"failed to update statistics preference"}`, http.StatusInternalServerError)
		return
	}
	affected, _ := result.RowsAffected()
	if affected != 1 {
		http.Error(w, `{"error":"user not found"}`, http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"global_stats_opt_in":    enabled == 1,
		"global_stats_opt_in_at": enabledAt,
	})
}

func (h *GlobalStatsHandler) Global(w http.ResponseWriter, r *http.Request) {
	// Consent changes and freshly synced sessions must be reflected on the next
	// request. Do not let a browser or intermediary retain a pre-opt-in zero.
	w.Header().Set("Cache-Control", "no-store")
	rangeName, floor, ok := globalStatsRange(r.URL.Query().Get("range"), time.Now())
	if !ok {
		http.Error(w, `{"error":"range must be one of 90days, year, all"}`, http.StatusBadRequest)
		return
	}

	var participants int
	if err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT COUNT(*) FROM users
		WHERE global_stats_opt_in = 1 AND is_suspended = 0
	`).Scan(&participants); err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	rows, err := h.DB.SQL.QueryContext(r.Context(), `
		SELECT
			ls.episode_id, ls.podcast_id, ls.podcast_title, ls.categories_json,
			ls.started_at, ls.ended_at, ls.wall_clock_ms, ls.audio_listened_ms,
			ls.speed_saved_ms, ls.silence_saved_ms, ls.manual_skipped_ms,
			ls.intro_outro_skipped_ms, ls.speed_weighted_ms,
			u.id, u.username
		FROM listening_sessions ls
		JOIN users u ON u.id = ls.user_id
		WHERE u.global_stats_opt_in = 1
		  AND u.is_suspended = 0
		  AND ls.started_at >= ?
		ORDER BY ls.started_at ASC
	`, floor)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	weekdayTotals := make([]int64, 7)
	hourTotals := make([]int64, 24)
	dayTotals := map[string]int64{}
	categories := map[string]int64{}
	podcasts := map[string]*globalPodcastAccumulator{}
	listeners := map[string]*globalListenerAccumulator{}
	allDays := map[string]struct{}{}
	episodeIDs := map[string]struct{}{}
	podcastIDs := map[string]struct{}{}
	var totalWall, totalAudio, speedSaved, silenceSaved, manualSkipped, introOutroSkipped, speedWeighted int64
	var sessionCount int

	for rows.Next() {
		var episodeID, podcastID, podcastTitle, categoriesJSON, userID, username string
		var startedAt, endedAt, wallMS, audioMS, speedSavedMS, silenceSavedMS, manualSkippedMS, introOutroSkippedMS, speedWeightedMS int64
		if err := rows.Scan(
			&episodeID, &podcastID, &podcastTitle, &categoriesJSON,
			&startedAt, &endedAt, &wallMS, &audioMS,
			&speedSavedMS, &silenceSavedMS, &manualSkippedMS,
			&introOutroSkippedMS, &speedWeightedMS, &userID, &username,
		); err != nil {
			http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
			return
		}
		wallMS = boundedMetric(wallMS, maxListeningSessionSpanMS)
		sessionCount++
		totalWall += wallMS
		totalAudio += boundedMetric(audioMS, maxListeningSessionSpanMS*4)
		speedSaved += boundedMetric(speedSavedMS, maxListeningSessionSpanMS*4)
		silenceSaved += boundedMetric(silenceSavedMS, maxListeningSessionSpanMS*4)
		manualSkipped += boundedMetric(manualSkippedMS, maxListeningSessionSpanMS*4)
		introOutroSkipped += boundedMetric(introOutroSkippedMS, maxListeningSessionSpanMS*4)
		speedWeighted += boundedMetric(speedWeightedMS, maxListeningSessionSpanMS*4)
		episodeIDs[episodeID] = struct{}{}
		podcastIDs[podcastID] = struct{}{}

		var labels []string
		_ = json.Unmarshal([]byte(categoriesJSON), &labels)
		label := "Uncategorised"
		for _, candidate := range labels {
			if candidate != "" {
				label = candidate
				break
			}
		}
		categories[label] += wallMS

		podcast := podcasts[podcastID]
		if podcast == nil {
			podcast = &globalPodcastAccumulator{
				ID: podcastID, Title: podcastTitle, Episodes: map[string]struct{}{},
			}
			podcasts[podcastID] = podcast
		}
		podcast.MS += wallMS
		podcast.Episodes[episodeID] = struct{}{}

		listener := listeners[userID]
		if listener == nil {
			listener = &globalListenerAccumulator{
				Username: username,
				Days:     map[string]struct{}{},
				Podcasts: map[string]struct{}{},
			}
			listeners[userID] = listener
		}
		listener.MS += wallMS
		listener.Podcasts[podcastID] = struct{}{}

		distributeGlobalSession(startedAt, endedAt, wallMS, func(timestamp, portion int64) {
			at := time.UnixMilli(timestamp).UTC()
			day := at.Format("2006-01-02")
			dayTotals[day] += portion
			allDays[day] = struct{}{}
			listener.Days[day] = struct{}{}
			weekdayTotals[int(at.Weekday())] += portion
			hourTotals[at.Hour()] += portion
		})
	}
	if err := rows.Err(); err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	podcastRanking := make([]globalPodcastTotal, 0, len(podcasts))
	for _, item := range podcasts {
		title := item.Title
		if title == "" {
			title = "Unknown show"
		}
		podcastRanking = append(podcastRanking, globalPodcastTotal{
			ID: item.ID, Title: title, MS: item.MS, Episodes: len(item.Episodes),
		})
	}
	sort.Slice(podcastRanking, func(i, j int) bool {
		if podcastRanking[i].MS == podcastRanking[j].MS {
			return podcastRanking[i].Title < podcastRanking[j].Title
		}
		return podcastRanking[i].MS > podcastRanking[j].MS
	})
	if len(podcastRanking) > 25 {
		podcastRanking = podcastRanking[:25]
	}
	for index := range podcastRanking {
		podcastRanking[index].Rank = index + 1
	}

	leaderboard := make([]globalListenerTotal, 0, len(listeners))
	for _, item := range listeners {
		leaderboard = append(leaderboard, globalListenerTotal{
			Username: item.Username, MS: item.MS,
			ActiveDays: len(item.Days), Podcasts: len(item.Podcasts),
		})
	}
	sort.Slice(leaderboard, func(i, j int) bool {
		if leaderboard[i].MS == leaderboard[j].MS {
			return leaderboard[i].Username < leaderboard[j].Username
		}
		return leaderboard[i].MS > leaderboard[j].MS
	})
	if len(leaderboard) > 50 {
		leaderboard = leaderboard[:50]
	}
	for index := range leaderboard {
		leaderboard[index].Rank = index + 1
	}

	categoryTotals := sortedGlobalTotals(categories, 10)
	days := make([]globalDayTotal, 0, len(dayTotals))
	for date, ms := range dayTotals {
		days = append(days, globalDayTotal{Date: date, MS: ms})
	}
	sort.Slice(days, func(i, j int) bool { return days[i].Date < days[j].Date })

	averageSpeed := 1.0
	if totalWall > 0 {
		averageSpeed = float64(speedWeighted) / float64(totalWall)
	}
	totalSaved := speedSaved + silenceSaved + manualSkipped + introOutroSkipped
	baselineAudio := totalWall + totalSaved
	if totalAudio > baselineAudio {
		baselineAudio = totalAudio
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"generated_at":           time.Now().UnixMilli(),
		"range":                  rangeName,
		"timezone":               "UTC",
		"participants":           participants,
		"total_wall_ms":          totalWall,
		"baseline_audio_ms":      baselineAudio,
		"total_saved_ms":         totalSaved,
		"speed_saved_ms":         speedSaved,
		"silence_saved_ms":       silenceSaved,
		"manual_skipped_ms":      manualSkipped,
		"intro_outro_skipped_ms": introOutroSkipped,
		"average_speed":          averageSpeed,
		"active_days":            len(allDays),
		"listening_sessions":     sessionCount,
		"episodes":               len(episodeIDs),
		"podcasts":               len(podcastIDs),
		"weekday_totals":         weekdayTotals,
		"hour_totals":            hourTotals,
		"day_totals":             days,
		"category_totals":        categoryTotals,
		"podcast_rankings":       podcastRanking,
		"listener_leaderboard":   leaderboard,
	})
}

func globalStatsRange(value string, now time.Time) (string, int64, bool) {
	switch value {
	case "", "year":
		return "year", time.Date(now.Year(), 1, 1, 0, 0, 0, 0, time.UTC).UnixMilli(), true
	case "90days":
		return "90days", now.Add(-90 * 24 * time.Hour).UnixMilli(), true
	case "all":
		return "all", 0, true
	default:
		return "", 0, false
	}
}

func boundedMetric(value, maximum int64) int64 {
	if value < 0 {
		return 0
	}
	if value > maximum {
		return maximum
	}
	return value
}

func distributeGlobalSession(start, end, wallMS int64, consume func(timestamp, portion int64)) {
	if wallMS <= 0 {
		return
	}
	if end <= start {
		consume(start, wallMS)
		return
	}
	if end-start > maxListeningSessionSpanMS {
		end = start + maxListeningSessionSpanMS
	}
	span := end - start
	cursor := start
	var distributed int64
	for cursor < end {
		at := time.UnixMilli(cursor).UTC()
		nextHour := at.Truncate(time.Hour).Add(time.Hour).UnixMilli()
		boundary := end
		if nextHour < boundary {
			boundary = nextHour
		}
		portion := wallMS * (boundary - cursor) / span
		if boundary == end {
			portion = wallMS - distributed
		}
		if portion > 0 {
			consume(cursor, portion)
			distributed += portion
		}
		cursor = boundary
	}
}

func sortedGlobalTotals(values map[string]int64, limit int) []globalTimeTotal {
	items := make([]globalTimeTotal, 0, len(values))
	for label, ms := range values {
		items = append(items, globalTimeTotal{Label: label, MS: ms})
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].MS == items[j].MS {
			return items[i].Label < items[j].Label
		}
		return items[i].MS > items[j].MS
	})
	if len(items) > limit {
		items = items[:limit]
	}
	return items
}

func writeJSON(w http.ResponseWriter, status int, value interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

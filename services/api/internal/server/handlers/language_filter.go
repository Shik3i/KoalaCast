package handlers

import (
	"context"
	"strings"

	"github.com/Shik3i/KoalaCast/services/api/internal/itunes"
	"github.com/Shik3i/KoalaCast/services/api/internal/lang"
)

// languageFilterOverfetch is how much extra upstream data Discover requests
// when a language filter is active. Charts are region-scoped, not language-
// scoped, so a "de" chart is a mix; without overfetching, filtering a 60-item
// chart down to German could leave a near-empty page.
const languageFilterOverfetch = 3

// resolveLanguages upgrades the heuristically-detected language on each result
// to the authoritative RSS <language> value for any feed already stored
// locally. Results are matched by feed URL, so chart entries (which carry no
// feed URL) keep their detected value.
//
// Results are modified in place. A DB error is not fatal: the detected
// languages simply stand on their own.
func (h *PodcastHandler) resolveLanguages(ctx context.Context, results []itunes.PodcastResult) {
	if h.DB == nil || len(results) == 0 {
		return
	}

	feedURLs := make([]string, 0, len(results))
	seen := make(map[string]bool, len(results))
	for _, r := range results {
		if r.FeedURL == "" || seen[r.FeedURL] {
			continue
		}
		seen[r.FeedURL] = true
		feedURLs = append(feedURLs, r.FeedURL)
	}
	if len(feedURLs) == 0 {
		return
	}

	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(feedURLs)), ",")
	args := make([]interface{}, len(feedURLs))
	for i, u := range feedURLs {
		args[i] = u
	}

	rows, err := h.DB.SQL.QueryContext(ctx,
		"SELECT feed_url, language FROM podcasts WHERE feed_url IN ("+placeholders+")", args...)
	if err != nil {
		return
	}
	defer rows.Close()

	known := make(map[string]string, len(feedURLs))
	for rows.Next() {
		var feedURL, language string
		if err := rows.Scan(&feedURL, &language); err != nil {
			continue
		}
		if code := lang.Normalize(language); code != "" {
			known[feedURL] = code
		}
	}

	for i := range results {
		if code, ok := known[results[i].FeedURL]; ok {
			results[i].Language = code
		}
	}
}

// filterByLanguage drops results that are definitely not in one of the wanted
// languages. Results whose language could not be determined are kept — see
// lang.Matches for why. An empty wanted list returns the input untouched.
func filterByLanguage(results []itunes.PodcastResult, wanted []string) []itunes.PodcastResult {
	if len(wanted) == 0 {
		return results
	}
	out := make([]itunes.PodcastResult, 0, len(results))
	for _, r := range results {
		if lang.Matches(r.Language, wanted) {
			out = append(out, r)
		}
	}
	return out
}

// filterByCategory drops results that do not carry the given category. Matching
// is case-insensitive across every category on the result, so "true crime"
// matches an iTunes "True Crime" genre. An empty or "all" category is a no-op.
func filterByCategory(results []itunes.PodcastResult, category string) []itunes.PodcastResult {
	category = strings.ToLower(strings.TrimSpace(category))
	if category == "" || category == "all" {
		return results
	}
	out := make([]itunes.PodcastResult, 0, len(results))
	for _, r := range results {
		if strings.ToLower(strings.TrimSpace(r.Category)) == category {
			out = append(out, r)
			continue
		}
		for _, c := range r.Categories {
			if strings.ToLower(strings.TrimSpace(c)) == category {
				out = append(out, r)
				break
			}
		}
	}
	return out
}

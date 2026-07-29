package rss

import "sort"

// RecentEpisodes returns the newest bounded feed window. Published items are
// ordered by date; undated items retain publisher order after dated items.
func RecentEpisodes(episodes []ParsedEpisode, limit int) []ParsedEpisode {
	if limit <= 0 || len(episodes) <= limit {
		return episodes
	}
	recent := append([]ParsedEpisode(nil), episodes...)
	sort.SliceStable(recent, func(i, j int) bool {
		if recent[i].HasPubDate != recent[j].HasPubDate {
			return recent[i].HasPubDate
		}
		if !recent[i].HasPubDate {
			return false
		}
		return recent[i].PubDate.After(recent[j].PubDate)
	})
	return recent[:limit]
}

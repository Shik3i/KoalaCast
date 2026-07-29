package rss

import (
	"testing"
	"time"
)

func TestRecentEpisodesSortsDatedItemsWithoutMutatingInput(t *testing.T) {
	episodes := []ParsedEpisode{
		{Title: "older", HasPubDate: true, PubDate: time.Unix(10, 0)},
		{Title: "undated"},
		{Title: "newest", HasPubDate: true, PubDate: time.Unix(30, 0)},
		{Title: "middle", HasPubDate: true, PubDate: time.Unix(20, 0)},
	}
	recent := RecentEpisodes(episodes, 2)
	if recent[0].Title != "newest" || recent[1].Title != "middle" {
		t.Fatalf("unexpected recent order: %q, %q", recent[0].Title, recent[1].Title)
	}
	if episodes[0].Title != "older" || episodes[1].Title != "undated" {
		t.Fatal("RecentEpisodes mutated publisher order")
	}
}

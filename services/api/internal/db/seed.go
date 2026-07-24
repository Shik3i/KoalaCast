package db

import (
	"context"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

type SeedPodcast struct {
	Title       string
	Author      string
	Description string
	FeedURL     string
	ArtworkURL  string
	Link        string
}

var DefaultSeedPodcasts = []SeedPodcast{
	{
		Title:       "Syntax - Tasty Web Development",
		Author:      "Wes Bos & Scott Tolinski",
		Description: "Full stack developers Wes Bos and Scott Tolinski break down web development concepts, JavaScript frameworks, CSS tricks, and developer lifestyle.",
		FeedURL:     "https://feed.syntax.fm",
		ArtworkURL:  "https://images.syntax.fm/syntax-banner.png",
		Link:        "https://syntax.fm",
	},
	{
		Title:       "The Changelog: Software Development",
		Author:      "Changelog Media",
		Description: "Conversations with the hackers, leaders, and innovators of software development, open source, AI, and technology culture.",
		FeedURL:     "https://changelog.com/podcast/feed",
		ArtworkURL:  "https://cdn.changelog.com/uploads/covers/the-changelog-original.png",
		Link:        "https://changelog.com",
	},
	{
		Title:       "ShopTalk Show",
		Author:      "Dave Rupert & Chris Coyier",
		Description: "A weekly podcast about front-end web design, UX, CSS, JavaScript, and web performance hosted by Dave Rupert and Chris Coyier.",
		FeedURL:     "https://shoptalkshow.com/feed/podcast/",
		ArtworkURL:  "https://shoptalkshow.com/wp-content/themes/shoptalk2021/images/shoptalk-logo.jpg",
		Link:        "https://shoptalkshow.com",
	},
	{
		Title:       "TED Radio Hour",
		Author:      "NPR",
		Description: "Unlocking big ideas from the world’s most fascinating thinkers. Exploring life’s biggest questions through TED Talks and interviews.",
		FeedURL:     "https://feeds.npr.org/510298/podcast.xml",
		ArtworkURL:  "https://media.npr.org/assets/img/2022/09/20/ted_radio_hour_sq_tile-4e2a716c5efdf393b4e6734c56e2eb9b32525dfd.jpg",
		Link:        "https://npr.org",
	},
}

func (db *DB) SeedDefaultPodcasts(ctx context.Context, logger *slog.Logger) {
	var count int
	err := db.SQL.QueryRowContext(ctx, "SELECT COUNT(*) FROM podcasts").Scan(&count)
	if err != nil || count > 0 {
		return // Database already seeded or populated
	}

	logger.Info("seeding default podcast catalog into database")
	nowMs := time.Now().UnixMilli()

	for _, pod := range DefaultSeedPodcasts {
		podID := uuid.New().String()
		_, err := db.SQL.ExecContext(ctx, `
			INSERT INTO podcasts (
				id, feed_url, title, description, author, artwork_url, link, language,
				explicit, copyright, update_frequency_ms, last_fetch_attempt_at,
				last_successful_fetch_at, next_scheduled_fetch_at, created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, 'en', 0, '', 86400000, 0, 0, 0, ?, ?)
		`, podID, pod.FeedURL, pod.Title, pod.Description, pod.Author, pod.ArtworkURL, pod.Link, nowMs, nowMs)
		if err != nil {
			logger.Warn("failed to seed podcast", "title", pod.Title, "error", err)
		}
	}
}

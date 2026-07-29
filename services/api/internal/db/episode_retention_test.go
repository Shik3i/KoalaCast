package db

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestPrunePodcastEpisodesKeepsRecentAndDurableState(t *testing.T) {
	database, err := OpenDB(
		filepath.Join(t.TempDir(), "retention.db"),
		slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError})),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	ctx := context.Background()
	now := time.Now().UnixMilli()
	if _, err := database.SQL.ExecContext(ctx, `
		INSERT INTO users (
			id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at
		) VALUES ('user-retention', 'Retention', 'retention', 'hash', 'recovery', ?, ?);
		INSERT INTO podcasts (id, feed_url, title, created_at, updated_at)
		VALUES ('pod-retention', 'https://example.com/feed.xml', 'Retention', ?, ?)
	`, now, now, now, now); err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 25; index++ {
		id := fmt.Sprintf("ep-%02d", index)
		if _, err := database.SQL.ExecContext(ctx, `
			INSERT INTO episodes (
				id, podcast_id, stable_identity_key, title, pub_date, has_pub_date,
				enclosure_url, created_at
			) VALUES (?, 'pod-retention', ?, ?, ?, 1, ?, ?)
		`, id, id, id, index, "https://example.com/"+id+".mp3", now+int64(index)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := database.SQL.ExecContext(ctx, `
		INSERT INTO favorites (user_id, episode_id, created_at)
		VALUES ('user-retention', 'ep-00', ?)
	`, now); err != nil {
		t.Fatal(err)
	}

	deleted, err := PrunePodcastEpisodes(ctx, database.SQL, "pod-retention", 20)
	if err != nil {
		t.Fatal(err)
	}
	if deleted != 4 {
		t.Fatalf("deleted=%d, want 4", deleted)
	}
	var remaining, protected int
	_ = database.SQL.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM episodes WHERE podcast_id = 'pod-retention'").Scan(&remaining)
	_ = database.SQL.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM episodes WHERE id = 'ep-00'").Scan(&protected)
	if remaining != 21 || protected != 1 {
		t.Fatalf("remaining=%d protected=%d, want 21 and 1", remaining, protected)
	}
}

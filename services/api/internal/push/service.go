package push

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"

	webpush "github.com/SherClockHolmes/webpush-go"
	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	"github.com/Shik3i/KoalaCast/services/api/internal/worker"
)

type Service struct {
	db     *db.DB
	cfg    *config.Config
	logger *slog.Logger
	client *http.Client
}

func NewService(database *db.DB, cfg *config.Config, logger *slog.Logger) *Service {
	return &Service{
		db:     database,
		cfg:    cfg,
		logger: logger,
		client: rss.NewSafeHTTPClient(rss.SafeTransportConfig{}),
	}
}

func (s *Service) Configured() bool {
	return s != nil && s.cfg.WebPushVAPIDPublicKey != "" && s.cfg.WebPushVAPIDPrivateKey != ""
}

// pushTopic derives the RFC 8030 Topic header for a podcast.
//
// The header is capped at 32 characters from the URL-safe base64 alphabet, and
// push services reject the whole request when it is longer. "podcast-" plus a
// 36-character UUID is 44, so every new-episode notification was answered with
// a 400 and no browser ever received one. A hash keeps the collapsing behaviour
// the topic is for — a second notification for the same show replaces the first
// one still queued — inside the length the spec allows.
func pushTopic(podcastID string) string {
	sum := sha256.Sum256([]byte("podcast:" + podcastID))
	return base64.RawURLEncoding.EncodeToString(sum[:])[:32]
}

func (s *Service) NotifyNewEpisodes(
	ctx context.Context,
	podcastID string,
	podcastTitle string,
	episodes []worker.NewEpisode,
) {
	if !s.Configured() || len(episodes) == 0 {
		return
	}

	rows, err := s.db.SQL.QueryContext(ctx, `
		SELECT DISTINCT wps.endpoint, wps.p256dh, wps.auth, wps.locale
		FROM web_push_subscriptions wps
		JOIN subscriptions sub ON sub.user_id = wps.user_id
		WHERE sub.podcast_id = ?
		  AND sub.is_deleted = 0
		  AND COALESCE((
			SELECT CASE
				WHEN sl.action = 'upsert'
				THEN COALESCE(
					json_extract(sl.payload_json, '$.notify_new_episodes'),
					json_extract(sl.payload_json, '$.notifyNewEpisodes'),
					0
				)
				ELSE 0
			END
			FROM sync_log sl
			WHERE sl.user_id = sub.user_id
			  AND sl.entity_type = 'podcast_settings'
			  AND sl.entity_id = sub.podcast_id
			ORDER BY sl.server_cursor DESC
			LIMIT 1
		  ), 0) = 1
	`, podcastID)
	if err != nil {
		s.logger.Warn("failed to select web push subscriptions", "podcast_id", podcastID, "error", err)
		return
	}

	// Drain the cursor before sending anything. Each delivery is a network round
	// trip to a third-party push service, and the loop below also deletes expired
	// registrations — holding a read cursor open across both, on the same SQLite
	// database, is how a popular show's notification run blocks its own writes.
	type target struct{ endpoint, p256dh, auth, locale string }
	targets := make([]target, 0)
	for rows.Next() {
		var item target
		if err := rows.Scan(&item.endpoint, &item.p256dh, &item.auth, &item.locale); err != nil {
			continue
		}
		targets = append(targets, item)
	}
	if err := rows.Err(); err != nil {
		s.logger.Warn("failed to read web push subscriptions", "podcast_id", podcastID, "error", err)
	}
	rows.Close()

	for _, item := range targets {
		endpoint, p256dh, auth, locale := item.endpoint, item.p256dh, item.auth, item.locale
		body := episodes[0].Title
		if len(episodes) > 1 {
			if locale == "de" {
				body = fmt.Sprintf("%d neue Folgen", len(episodes))
			} else {
				body = fmt.Sprintf("%d new episodes", len(episodes))
			}
		}
		payload, _ := json.Marshal(map[string]any{
			"title": podcastTitle,
			"body":  body,
			"tag":   "new-episodes-" + podcastID,
			"url":   "/podcast/" + podcastID,
		})
		subscription := &webpush.Subscription{
			Endpoint: endpoint,
			Keys: webpush.Keys{
				P256dh: p256dh,
				Auth:   auth,
			},
		}
		response, sendErr := webpush.SendNotificationWithContext(ctx, payload, subscription, &webpush.Options{
			HTTPClient:      s.client,
			Subscriber:      s.cfg.WebPushVAPIDSubject,
			VAPIDPublicKey:  s.cfg.WebPushVAPIDPublicKey,
			VAPIDPrivateKey: s.cfg.WebPushVAPIDPrivateKey,
			TTL:             86400,
			Urgency:         webpush.UrgencyNormal,
			Topic:           pushTopic(podcastID),
		})
		if sendErr != nil {
			s.logger.Warn("web push send failed", "podcast_id", podcastID, "error", sendErr)
			continue
		}
		_, _ = io.Copy(io.Discard, response.Body)
		_ = response.Body.Close()
		if response.StatusCode == http.StatusNotFound || response.StatusCode == http.StatusGone {
			_, _ = s.db.SQL.ExecContext(ctx, "DELETE FROM web_push_subscriptions WHERE endpoint = ?", endpoint)
		} else if response.StatusCode < 200 || response.StatusCode >= 300 {
			s.logger.Warn("web push endpoint rejected notification", "podcast_id", podcastID, "status", response.StatusCode)
		}
	}
}

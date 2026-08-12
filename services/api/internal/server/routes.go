package server

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"strings"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/podcastindex"
	pushservice "github.com/Shik3i/KoalaCast/services/api/internal/push"
	"github.com/Shik3i/KoalaCast/services/api/internal/server/handlers"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
	"github.com/Shik3i/KoalaCast/services/api/internal/worker"
	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
)

func NewRouter(cfg *config.Config, database *db.DB, feedWorker *worker.FeedWorker, logger *slog.Logger) http.Handler {
	r := chi.NewRouter()

	// Middlewares
	r.Use(customMiddleware.RealIP(cfg.TrustedProxies))
	r.Use(customMiddleware.SecurityHeaders)
	r.Use(customMiddleware.CORS(cfg.AllowedCORSOrigins))
	r.Use(middleware.Compress(5))
	r.Use(customMiddleware.RequestID)
	r.Use(customMiddleware.Logger(logger))
	r.Use(customMiddleware.ErrorRecorder(database, logger))
	r.Use(middleware.Recoverer)

	healthHandler := &handlers.HealthHandler{DB: database}
	podcastIdxClient := podcastindex.NewClient(cfg.PodcastIndexKey, cfg.PodcastIndexSecret)

	podcastHandler := &handlers.PodcastHandler{
		DB:           database,
		PodcastIndex: podcastIdxClient,
		Worker:       feedWorker,
		MaxResponseB: cfg.FeedMaxResponseBytes,
		MaxEpisodes:  cfg.FeedMaxStoredEpisodes,
	}

	authHandler := &handlers.AuthHandler{
		DB:     database,
		Config: cfg,
	}
	pushService := pushservice.NewService(database, cfg, logger)
	feedWorker.SetNotifier(pushService)
	pushHandler := &handlers.PushHandler{DB: database, Config: cfg}

	syncHandler := &handlers.SyncHandler{
		DB: database,
	}

	globalStatsHandler := &handlers.GlobalStatsHandler{
		DB: database,
	}

	opmlHandler := &handlers.OPMLHandler{
		DB:         database,
		IngestFeed: podcastHandler.IngestFeedURL,
	}

	adminHandler := &handlers.AdminHandler{
		DB:     database,
		Config: cfg,
		Worker: feedWorker,
	}

	proxyHandler := handlers.NewProxyHandler(cfg.AudioEffectsProxyEnabled)

	// Operational Probes
	r.Get("/healthz", healthHandler.Healthz)
	r.Get("/readyz", healthHandler.Readyz)

	// API v1 routes
	r.Route("/api/v1", func(r chi.Router) {
		r.Get("/healthz", healthHandler.Healthz)
		r.Get("/readyz", healthHandler.Readyz)
		r.Get("/config", func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]bool{
				"audio_effects_proxy_enabled": cfg.AudioEffectsProxyEnabled,
			})
		})

		// Proxy endpoints for CORS-safe chapters/transcripts and privacy-safe cached
		// images. Each fetches an arbitrary client-supplied URL server-side, so the
		// group is throttled per client IP to bound outbound-fetch, open-relay and
		// image-decode CPU/RAM abuse. The cap is generous (a discover page loads many
		// artworks) but still bounds a hostile client.
		proxyLimiter := customMiddleware.NewRateLimiter(600, 1*time.Minute)
		r.Group(func(r chi.Router) {
			r.Use(proxyLimiter.Limit)
			r.Get("/proxy/chapters", proxyHandler.GetChapters)
			r.Head("/proxy/chapters", proxyHandler.GetChapters)
			r.Get("/proxy/transcript", proxyHandler.GetTranscript)
			r.Head("/proxy/transcript", proxyHandler.GetTranscript)
			r.Get("/proxy/image", proxyHandler.GetImageProxy)
			r.Head("/proxy/image", proxyHandler.GetImageProxy)
		})
		// Audio effects may legitimately open multiple range requests while seeking
		// or switching episodes. Keep abuse bounded without breaking normal playback.
		audioProxyLimiter := customMiddleware.NewRateLimiter(180, 1*time.Hour)
		r.Group(func(r chi.Router) {
			r.Use(audioProxyLimiter.Limit)
			r.Get("/proxy/audio", proxyHandler.GetAudioProxy)
			r.Head("/proxy/audio", proxyHandler.GetAudioProxy)
			// Resolves a redirect chain so the browser can stream from the final
			// host itself. Not gated on the audio proxy: it moves no audio.
			r.Get("/proxy/audio/resolve", proxyHandler.GetAudioResolve)
		})

		// Podcasts & Discovery. Discover and Search proxy out to iTunes / Podcast
		// Index, so they are throttled per client IP to stop the server being used to
		// hammer those upstreams. The DB-backed reads below stay unthrottled (the
		// Inbox legitimately fetches many at once).
		discoveryLimiter := customMiddleware.NewRateLimiter(60, 1*time.Minute)
		r.Group(func(r chi.Router) {
			r.Use(discoveryLimiter.Limit)
			r.Get("/podcasts/discover", podcastHandler.Discover)
			r.Get("/podcasts/search", podcastHandler.Search)
		})
		r.Get("/podcasts/{id}", podcastHandler.GetPodcast)
		r.Get("/podcasts/{id}/episodes", podcastHandler.GetEpisodes)
		r.Get("/episodes/{id}", podcastHandler.GetEpisode)
		r.Get("/episodes/{id}/transcript", podcastHandler.GetEpisodeTranscript)

		// Public aggregates contain only data from accounts that explicitly opted
		// in. No raw sessions or identifiers are returned. The aggregation scans
		// eligible sessions, so bound repeated public requests per client.
		globalStatsLimiter := customMiddleware.NewRateLimiter(120, 1*time.Minute)
		r.Group(func(r chi.Router) {
			r.Use(globalStatsLimiter.Limit)
			r.Get("/stats/global", globalStatsHandler.Global)
		})

		// AddFeed triggers an unauthenticated server-side fetch + DB writes, so it
		// is throttled per client IP to bound outbound-fetch and DB-growth abuse.
		feedLimiter := customMiddleware.NewRateLimiter(20, 1*time.Minute)
		r.Group(func(r chi.Router) {
			r.Use(feedLimiter.Limit)
			r.Post("/podcasts/feed", podcastHandler.AddFeed)
		})

		// Authentication & Recovery with Rate Limiting
		authLimiter := customMiddleware.NewRateLimiter(10, 1*time.Minute)
		r.Group(func(r chi.Router) {
			r.Use(authLimiter.Limit)
			r.Post("/auth/register", authHandler.Register)
			r.Post("/auth/login", authHandler.Login)
			r.Post("/auth/device/login", authHandler.DeviceLogin)
			r.Post("/auth/recovery/verify", authHandler.VerifyRecoveryCode)
		})
		r.With(customMiddleware.OptionalAuth(database)).Get("/auth/status", authHandler.Status)

		// OPML import works for anonymous local-first users too: it ingests the feeds
		// server-side and returns the resolved podcasts so the client can store the
		// subscriptions on-device. Signed-in accounts additionally get server-side
		// subscriptions. Each request can fan out to many outbound fetches, so it is
		// tightly throttled per client IP.
		opmlLimiter := customMiddleware.NewRateLimiter(5, 1*time.Minute)
		r.Group(func(r chi.Router) {
			r.Use(customMiddleware.OptionalAuth(database))
			r.Use(opmlLimiter.Limit)
			r.Post("/opml/import", opmlHandler.Import)
		})

		// Authenticated Routes
		syncLimiter := customMiddleware.NewRateLimiter(240, 1*time.Minute)
		r.Group(func(r chi.Router) {
			r.Use(customMiddleware.AuthRequired(database))

			r.Get("/auth/me", authHandler.Me)
			r.Post("/auth/logout", authHandler.Logout)
			r.Get("/auth/sessions", authHandler.ListSessions)
			r.Delete("/auth/sessions/{id}", authHandler.RevokeSession)
			// Deletion and export are destructive/bulk and re-check the credential
			// themselves, so they sit behind the same tight limiter as sign-in
			// rather than the general authenticated allowance.
			r.With(authLimiter.Limit).Delete("/auth/account", authHandler.DeleteAccount)
			r.With(authLimiter.Limit).Delete("/auth/data", authHandler.DeleteSynchronizedData)
			r.With(authLimiter.Limit).Get("/auth/export", authHandler.ExportAccount)
			r.Get("/stats/preferences", globalStatsHandler.GetPreference)
			r.Put("/stats/preferences", globalStatsHandler.UpdatePreference)
			r.Get("/push/config", pushHandler.GetConfig)
			r.Post("/push/subscriptions", pushHandler.Subscribe)
			r.Delete("/push/subscriptions", pushHandler.Unsubscribe)

			// Cross-Device Sync Engine
			r.With(syncLimiter.LimitAuthenticated).Get("/sync", syncHandler.Pull)
			r.With(syncLimiter.LimitAuthenticated).Get("/sync/snapshot", syncHandler.Snapshot)
			r.With(syncLimiter.LimitAuthenticated).Post("/sync", syncHandler.Push)
			r.With(syncLimiter.LimitAuthenticated).Post("/sync/merge", syncHandler.MergeLocalData)

			// OPML export reads the account's server-side subscriptions (local-first
			// clients generate their OPML on-device instead).
			r.Get("/opml/export", opmlHandler.Export)
		})

		// Admin Interface Routes
		r.Group(func(r chi.Router) {
			r.Use(customMiddleware.AuthRequired(database))
			r.Use(adminHandler.RequireAdmin)

			r.Get("/admin/users", adminHandler.ListUsers)
			r.Post("/admin/registration/toggle", adminHandler.ToggleRegistration)
			r.Post("/admin/users/{id}/suspend", adminHandler.SuspendUser)
			r.Delete("/admin/users/{id}/sessions", adminHandler.RevokeUserSessions)
			r.Get("/admin/feed-health", adminHandler.FeedHealth)
			r.Get("/admin/errors", adminHandler.ListErrors)
			r.Post("/admin/podcasts/{id}/refresh", adminHandler.ManualRefreshFeed)
			r.Get("/admin/status", adminHandler.SystemStatus)
		})
	})

	// Native Go Static Web File Server (SvelteKit SPA) with index.html fallback
	webDir := os.Getenv("WEB_STATIC_DIR")
	if webDir == "" {
		webDir = "/app/web/build"
	}

	if _, err := os.Stat(webDir); err == nil {
		fileServer := http.FileServer(http.Dir(webDir))
		r.NotFound(func(w http.ResponseWriter, r *http.Request) {
			if strings.HasPrefix(r.URL.Path, "/api/") {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusNotFound)
				_, _ = w.Write([]byte(`{"error":"not found"}`))
				return
			}
			if strings.HasPrefix(r.URL.Path, "/_app/immutable/") {
				w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
			} else {
				switch strings.ToLower(filepath.Ext(r.URL.Path)) {
				case ".avif", ".jpg", ".jpeg", ".png", ".svg", ".webp", ".woff2":
					// These stable app-shell assets are versioned alongside each
					// deployment. A week avoids repeat-download warnings without
					// making a self-hoster's icon update sticky for a year.
					w.Header().Set("Cache-Control", "public, max-age=604800, stale-while-revalidate=86400")
				case ".txt", ".xml", ".webmanifest":
					w.Header().Set("Cache-Control", "public, max-age=3600, must-revalidate")
				}
			}

			path := filepath.Join(webDir, filepath.Clean(r.URL.Path))
			if info, err := os.Stat(path); err != nil || info.IsDir() {
				w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
				http.ServeFile(w, r, filepath.Join(webDir, "index.html"))
				return
			}
			fileServer.ServeHTTP(w, r)
		})
	}

	return r
}

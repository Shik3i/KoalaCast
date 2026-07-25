package server

import (
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"strings"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/podcastindex"
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
	r.Use(middleware.Recoverer)

	healthHandler := &handlers.HealthHandler{DB: database}
	podcastIdxClient := podcastindex.NewClient(cfg.PodcastIndexKey, cfg.PodcastIndexSecret)

	podcastHandler := &handlers.PodcastHandler{
		DB:           database,
		PodcastIndex: podcastIdxClient,
		Worker:       feedWorker,
		MaxResponseB: cfg.FeedMaxResponseBytes,
	}

	authHandler := &handlers.AuthHandler{
		DB:     database,
		Config: cfg,
	}

	syncHandler := &handlers.SyncHandler{
		DB: database,
	}

	opmlHandler := &handlers.OPMLHandler{
		DB:           database,
		MaxResponseB: cfg.FeedMaxResponseBytes,
	}

	adminHandler := &handlers.AdminHandler{
		DB:     database,
		Config: cfg,
		Worker: feedWorker,
	}

	proxyHandler := handlers.NewProxyHandler()

	// Operational Probes
	r.Get("/healthz", healthHandler.Healthz)
	r.Get("/readyz", healthHandler.Readyz)

	// API v1 routes
	r.Route("/api/v1", func(r chi.Router) {
		r.Get("/healthz", healthHandler.Healthz)
		r.Get("/readyz", healthHandler.Readyz)

		// Proxy endpoints for CORS-safe Chapters, Transcripts, and Privacy-Safe Cached Images
		r.Get("/proxy/chapters", proxyHandler.GetChapters)
		r.Head("/proxy/chapters", proxyHandler.GetChapters)
		r.Get("/proxy/transcript", proxyHandler.GetTranscript)
		r.Head("/proxy/transcript", proxyHandler.GetTranscript)
		r.Get("/proxy/image", proxyHandler.GetImageProxy)
		r.Head("/proxy/image", proxyHandler.GetImageProxy)

		// Podcasts & Discovery
		r.Get("/podcasts/discover", podcastHandler.Discover)
		r.Get("/podcasts/search", podcastHandler.Search)
		r.Get("/podcasts/{id}", podcastHandler.GetPodcast)
		r.Get("/podcasts/{id}/episodes", podcastHandler.GetEpisodes)
		r.Get("/episodes/{id}", podcastHandler.GetEpisode)
		r.Get("/episodes/{id}/transcript", podcastHandler.GetEpisodeTranscript)

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

		// Authenticated Routes
		r.Group(func(r chi.Router) {
			r.Use(customMiddleware.AuthRequired(database))

			r.Get("/auth/me", authHandler.Me)
			r.Post("/auth/logout", authHandler.Logout)
			r.Get("/auth/sessions", authHandler.ListSessions)
			r.Delete("/auth/sessions/{id}", authHandler.RevokeSession)

			// Cross-Device Sync Engine
			r.Get("/sync", syncHandler.Pull)
			r.Post("/sync", syncHandler.Push)
			r.Post("/sync/merge", syncHandler.MergeLocalData)

			// OPML Import / Export
			r.Post("/opml/import", opmlHandler.Import)
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
			if strings.HasPrefix(r.URL.Path, "/_app/immutable/") {
				w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
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

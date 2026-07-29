package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/auth"
	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/server"
	"github.com/Shik3i/KoalaCast/services/api/internal/worker"
)

func main() {
	cfg, err := config.LoadConfig()
	if err != nil {
		slog.Error("failed to load configuration", "error", err)
		os.Exit(1)
	}

	auth.SetPepper(cfg.PepperSecret)

	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel})
	logger := slog.New(handler)

	logger.Info("starting KoalaCast API service", "port", cfg.Port, "db", cfg.DatabasePath)

	database, err := db.OpenDB(cfg.DatabasePath, logger)
	if err != nil {
		logger.Error("failed to initialize database", "error", err)
		os.Exit(1)
	}
	defer database.Close()

	database.SeedDefaultPodcasts(context.Background(), logger)
	database.SeedAdmin(context.Background(), cfg.AdminUsername, cfg.AdminPassword, logger)

	// Initialize background feed worker pool
	feedWorker := worker.NewFeedWorker(database, cfg, logger)
	ctxWorker, cancelWorker := context.WithCancel(context.Background())
	defer cancelWorker()

	feedWorker.Start(ctxWorker)
	defer feedWorker.Stop()

	srv := server.NewServer(cfg, database, feedWorker, logger)

	// Graceful shutdown channel
	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)
	serverErr := make(chan error, 1)

	go func() {
		serverErr <- srv.Start()
	}()

	select {
	case <-stopChan:
		logger.Info("received shutdown signal")
	case err := <-serverErr:
		if err != nil {
			logger.Error("server runtime error", "error", err)
			cancelWorker()
			feedWorker.Stop()
			_ = database.Close()
			os.Exit(1)
		}
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		logger.Error("failed graceful shutdown", "error", err)
	} else {
		logger.Info("server shutdown complete")
	}
}

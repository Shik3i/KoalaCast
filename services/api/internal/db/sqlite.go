package db

import (
	"database/sql"
	"embed"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"

	_ "github.com/mattn/go-sqlite3"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

type DB struct {
	SQL *sql.DB
}

func OpenDB(dbPath string, logger *slog.Logger) (*DB, error) {
	// Ensure directory exists
	dir := filepath.Dir(dbPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create database directory: %w", err)
	}

	// Connection string with PRAGMAs
	dsn := fmt.Sprintf("%s?_journal_mode=WAL&_foreign_keys=ON&_busy_timeout=5000", dbPath)
	sqlDB, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to open sqlite database: %w", err)
	}

	// SQLite allows only a single writer. With a multi-connection pool, concurrent
	// writes (feed worker pool + sync handlers) race for the write lock and can
	// surface intermittent "database is locked" errors even with busy_timeout.
	// Serializing on one connection is the idiomatic fix for an embedded SQLite
	// writer and eliminates that class of failures. All query loops here are
	// scan-only (no query issued while rows are open), so this cannot deadlock.
	sqlDB.SetMaxOpenConns(1)

	if err := sqlDB.Ping(); err != nil {
		return nil, fmt.Errorf("failed to ping sqlite database: %w", err)
	}

	logger.Info("connected to sqlite database", "path", dbPath, "wal", true)

	db := &DB{SQL: sqlDB}
	if err := db.Migrate(logger); err != nil {
		return nil, fmt.Errorf("database migration failed: %w", err)
	}

	return db, nil
}

func (db *DB) Migrate(logger *slog.Logger) error {
	entries, err := migrationsFS.ReadDir("migrations")
	if err != nil {
		return fmt.Errorf("failed to read migrations dir: %w", err)
	}

	var upFiles []string
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".up.sql") {
			upFiles = append(upFiles, entry.Name())
		}
	}
	sort.Strings(upFiles)

	for _, file := range upFiles {
		content, err := migrationsFS.ReadFile("migrations/" + file)
		if err != nil {
			return fmt.Errorf("failed to read migration file %s: %w", file, err)
		}

		logger.Info("executing migration", "file", file)
		if _, err := db.SQL.Exec(string(content)); err != nil {
			return fmt.Errorf("failed to execute migration %s: %w", file, err)
		}
	}

	return nil
}

func (db *DB) Close() error {
	return db.SQL.Close()
}

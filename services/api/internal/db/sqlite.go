package db

import (
	"database/sql"
	"embed"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	sqlite3 "github.com/mattn/go-sqlite3"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// driverName is a custom driver registration whose ConnectHook applies the
// performance PRAGMAs to every pooled connection (DSN params only cover a subset
// and are easy to get wrong per-connection).
const driverName = "sqlite3_koalacast"

var registerOnce sync.Once

// connectPragmas run on every new connection. Rationale:
//   - busy_timeout: wait (don't error) when another connection holds the write lock.
//   - journal_mode=WAL: concurrent readers alongside a single writer.
//   - synchronous=NORMAL: safe with WAL (only a power-loss on the very last commit
//     can be lost, never corruption) and far faster than FULL.
//   - foreign_keys=ON: enforce referential integrity / ON DELETE CASCADE.
//   - cache_size=-65536: 64 MiB page cache per connection (negative = KiB).
//   - temp_store=MEMORY: temp b-trees/sorts in RAM.
//   - mmap_size=256MiB: memory-mapped reads cut read syscalls.
var connectPragmas = []string{
	"PRAGMA busy_timeout = 5000",
	"PRAGMA journal_mode = WAL",
	"PRAGMA synchronous = NORMAL",
	"PRAGMA foreign_keys = ON",
	"PRAGMA cache_size = -65536",
	"PRAGMA temp_store = MEMORY",
	"PRAGMA mmap_size = 268435456",
}

func registerDriver() {
	registerOnce.Do(func() {
		sql.Register(driverName, &sqlite3.SQLiteDriver{
			ConnectHook: func(conn *sqlite3.SQLiteConn) error {
				for _, p := range connectPragmas {
					if _, err := conn.Exec(p, nil); err != nil {
						return fmt.Errorf("apply %q: %w", p, err)
					}
				}
				return nil
			},
		})
	})
}

type DB struct {
	SQL *sql.DB
}

func OpenDB(dbPath string, logger *slog.Logger) (*DB, error) {
	registerDriver()

	// Ensure directory exists
	dir := filepath.Dir(dbPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create database directory: %w", err)
	}

	// _txlock=immediate makes every transaction BEGIN IMMEDIATE, acquiring the
	// write lock up front. This eliminates the classic SQLite deadlock where two
	// deferred transactions each hold a read lock and then both try to upgrade to
	// write (which busy_timeout cannot resolve). With it, a concurrent writer
	// simply waits on busy_timeout instead — so we can run a real connection pool
	// (concurrent readers + serialized writers) without "database is locked".
	dsn := fmt.Sprintf("file:%s?_txlock=immediate", dbPath)
	sqlDB, err := sql.Open(driverName, dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to open sqlite database: %w", err)
	}

	// Pool sizing: allow concurrent reads across cores while keeping the pool
	// bounded. Writes serialize at the SQLite file lock regardless of pool size;
	// _txlock=immediate + busy_timeout make that safe rather than error-prone.
	maxConns := runtime.NumCPU()
	if maxConns < 4 {
		maxConns = 4
	}
	sqlDB.SetMaxOpenConns(maxConns)
	sqlDB.SetMaxIdleConns(maxConns)
	sqlDB.SetConnMaxIdleTime(5 * time.Minute)
	sqlDB.SetConnMaxLifetime(time.Hour)

	if err := sqlDB.Ping(); err != nil {
		return nil, fmt.Errorf("failed to ping sqlite database: %w", err)
	}

	logger.Info("connected to sqlite database", "path", dbPath, "wal", true, "max_conns", maxConns)

	db := &DB{SQL: sqlDB}
	if err := db.Migrate(logger); err != nil {
		return nil, fmt.Errorf("database migration failed: %w", err)
	}

	// Refresh the query planner statistics after migrations so hot-path queries
	// (episodes by podcast+pubdate, sync_log by cursor) use the right indexes.
	if _, err := db.SQL.Exec("PRAGMA analysis_limit = 400; PRAGMA optimize;"); err != nil {
		logger.Warn("PRAGMA optimize failed (non-fatal)", "error", err)
	}

	return db, nil
}

func (db *DB) Migrate(logger *slog.Logger) error {
	// Track applied migrations so each .up.sql runs exactly once. Without this,
	// every startup re-ran all migrations, which breaks any non-idempotent step
	// (e.g. ALTER TABLE ADD COLUMN) as soon as the database has persisted data.
	if _, err := db.SQL.Exec(
		`CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY, applied_at INTEGER NOT NULL)`,
	); err != nil {
		return fmt.Errorf("failed to ensure schema_migrations table: %w", err)
	}

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
		var applied int
		if err := db.SQL.QueryRow(
			`SELECT COUNT(1) FROM schema_migrations WHERE version = ?`, file,
		).Scan(&applied); err != nil {
			return fmt.Errorf("failed to read migration state for %s: %w", file, err)
		}
		if applied > 0 {
			continue
		}

		content, err := migrationsFS.ReadFile("migrations/" + file)
		if err != nil {
			return fmt.Errorf("failed to read migration file %s: %w", file, err)
		}

		logger.Info("executing migration", "file", file)
		if _, err := db.SQL.Exec(string(content)); err != nil {
			// Self-heal databases that predate migration tracking: the schema
			// change may already be present from an earlier un-tracked run. Treat
			// "already exists" style errors as applied instead of crash-looping.
			if isAlreadyAppliedErr(err) {
				logger.Warn("migration already applied to existing schema; recording as done",
					"file", file, "detail", err.Error())
			} else {
				return fmt.Errorf("failed to execute migration %s: %w", file, err)
			}
		}

		if _, err := db.SQL.Exec(
			`INSERT OR IGNORE INTO schema_migrations (version, applied_at) VALUES (?, ?)`,
			file, time.Now().UnixMilli(),
		); err != nil {
			return fmt.Errorf("failed to record migration %s: %w", file, err)
		}
	}

	return nil
}

// isAlreadyAppliedErr reports whether a migration error just means the change is
// already present (e.g. re-adding a column/table on a pre-tracking database).
func isAlreadyAppliedErr(err error) bool {
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "duplicate column name") ||
		strings.Contains(msg, "already exists")
}

// Close runs a final optimize pass (cheap, uses the stats gathered this session)
// and a WAL checkpoint so the -wal file is folded back into the main database,
// then closes the pool.
func (db *DB) Close() error {
	_, _ = db.SQL.Exec("PRAGMA optimize;")
	_, _ = db.SQL.Exec("PRAGMA wal_checkpoint(TRUNCATE);")
	return db.SQL.Close()
}

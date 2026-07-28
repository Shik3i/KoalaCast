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
		tx, err := db.SQL.Begin()
		if err != nil {
			return fmt.Errorf("begin migration %s: %w", file, err)
		}

		var applied int
		if err := tx.QueryRow(
			`SELECT COUNT(1) FROM schema_migrations WHERE version = ?`, file,
		).Scan(&applied); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("failed to read migration state for %s: %w", file, err)
		}
		if applied > 0 {
			_ = tx.Rollback()
			continue
		}

		content, err := migrationsFS.ReadFile("migrations/" + file)
		if err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("failed to read migration file %s: %w", file, err)
		}

		logger.Info("executing migration", "file", file)
		reconciled, err := reconcileLegacyPartialMigration(tx, file)
		if err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("failed to reconcile migration %s: %w", file, err)
		}
		if !reconciled {
			if _, err := tx.Exec(string(content)); err != nil {
				_ = tx.Rollback()
				return fmt.Errorf("failed to execute migration %s: %w", file, err)
			}
		}

		if _, err := tx.Exec(
			`INSERT OR IGNORE INTO schema_migrations (version, applied_at) VALUES (?, ?)`,
			file, time.Now().UnixMilli(),
		); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("failed to record migration %s: %w", file, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %s: %w", file, err)
		}
	}

	return nil
}

// reconcileLegacyPartialMigration repairs schemas created before migration
// tracking existed. It recognizes only concrete historical columns; it never
// treats an arbitrary "already exists" error as proof that a whole file ran.
func reconcileLegacyPartialMigration(tx *sql.Tx, file string) (bool, error) {
	switch file {
	case "000002_device_token_expiry.up.sql":
		hasExpiry, err := tableHasColumn(tx, "device_credentials", "expires_at")
		if err != nil || !hasExpiry {
			return false, err
		}
		_, err = tx.Exec(`CREATE INDEX IF NOT EXISTS idx_device_credentials_token ON device_credentials(token_hash)`)
		return true, err
	case "000003_episode_transcripts.up.sql":
		hasTranscripts, err := tableHasColumn(tx, "episodes", "transcripts")
		return hasTranscripts, err
	case "000006_global_statistics_opt_in.up.sql":
		hasOptIn, err := tableHasColumn(tx, "users", "global_stats_opt_in")
		if err != nil {
			return false, err
		}
		hasOptInAt, err := tableHasColumn(tx, "users", "global_stats_opt_in_at")
		if err != nil {
			return false, err
		}
		if !hasOptIn && !hasOptInAt {
			return false, nil
		}
		if !hasOptIn {
			if _, err := tx.Exec(`ALTER TABLE users ADD COLUMN global_stats_opt_in INTEGER NOT NULL DEFAULT 0 CHECK (global_stats_opt_in IN (0, 1))`); err != nil {
				return false, err
			}
		}
		if !hasOptInAt {
			if _, err := tx.Exec(`ALTER TABLE users ADD COLUMN global_stats_opt_in_at INTEGER NOT NULL DEFAULT 0`); err != nil {
				return false, err
			}
		}
		if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS idx_users_global_stats_opt_in ON users(global_stats_opt_in, is_suspended)`); err != nil {
			return false, err
		}
		if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS idx_listening_sessions_started ON listening_sessions(started_at)`); err != nil {
			return false, err
		}
		return true, nil
	case "000007_episode_chapters.up.sql":
		hasChapters, err := tableHasColumn(tx, "episodes", "chapters_url")
		return hasChapters, err
	default:
		return false, nil
	}
}

func tableHasColumn(tx *sql.Tx, table, column string) (bool, error) {
	rows, err := tx.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return false, err
	}
	defer rows.Close()

	for rows.Next() {
		var cid int
		var name, columnType string
		var notNull, primaryKey int
		var defaultValue any
		if err := rows.Scan(&cid, &name, &columnType, &notNull, &defaultValue, &primaryKey); err != nil {
			return false, err
		}
		if name == column {
			return true, nil
		}
	}
	return false, rows.Err()
}

// Close runs a final optimize pass (cheap, uses the stats gathered this session)
// and a WAL checkpoint so the -wal file is folded back into the main database,
// then closes the pool.
func (db *DB) Close() error {
	_, _ = db.SQL.Exec("PRAGMA optimize;")
	_, _ = db.SQL.Exec("PRAGMA wal_checkpoint(TRUNCATE);")
	return db.SQL.Close()
}

package db

import (
	"context"
	"database/sql"
	"log/slog"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/auth"
	"github.com/google/uuid"
)

// SeedAdmin provisions an administrator account from the ADMIN_USERNAME /
// ADMIN_PASSWORD environment variables at startup. It is idempotent and safe to
// run on every boot:
//
//   - both values empty            -> no-op
//   - the user does not exist       -> create it with role=admin (a one-time
//     recovery code is logged at WARN so the operator can save it)
//   - the user exists but is not admin -> promote to admin; the password is left
//     untouched (we never overwrite an already-set password)
//   - the user exists and is admin  -> no-op
func (db *DB) SeedAdmin(ctx context.Context, username, password string, logger *slog.Logger) {
	username = strings.TrimSpace(username)
	if username == "" || password == "" {
		return
	}
	normalized := strings.ToLower(username)

	var existingID, existingRole string
	err := db.SQL.QueryRowContext(ctx,
		"SELECT id, role FROM users WHERE normalized_username = ?", normalized,
	).Scan(&existingID, &existingRole)

	if err == nil {
		// Account already present — only ever promote, never touch the password.
		if existingRole != "admin" {
			if _, e := db.SQL.ExecContext(ctx,
				"UPDATE users SET role = 'admin', updated_at = ? WHERE id = ?",
				time.Now().UnixMilli(), existingID,
			); e != nil {
				logger.Error("admin seed: failed to promote existing user", "error", e)
				return
			}
			logger.Info("admin seed: promoted existing user to admin", "username", username)
		}
		return
	}
	if err != sql.ErrNoRows {
		logger.Error("admin seed: lookup failed", "error", err)
		return
	}

	// No such user yet — create a fresh admin.
	pwdHash, e := auth.HashPassword(password)
	if e != nil {
		logger.Error("admin seed: password hash failed", "error", e)
		return
	}
	recoveryCode, recoveryHash, e := auth.GenerateRecoveryCode()
	if e != nil {
		logger.Error("admin seed: recovery code generation failed", "error", e)
		return
	}
	now := time.Now().UnixMilli()
	if _, e := db.SQL.ExecContext(ctx, `
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, role, is_suspended, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, 'admin', 0, ?, ?)
	`, uuid.New().String(), username, normalized, pwdHash, recoveryHash, now, now); e != nil {
		logger.Error("admin seed: insert failed", "error", e)
		return
	}

	logger.Warn("admin seed: created admin account — SAVE THIS RECOVERY CODE (shown once)",
		"username", username, "recovery_code", recoveryCode)
}

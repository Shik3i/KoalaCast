package handlers

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/auth"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
)

type freshCredentialRequest struct {
	Password     string `json:"password"`
	RecoveryCode string `json:"recovery_code"`
}

func (h *AuthHandler) verifyFreshCredential(ctx context.Context, userID string, req freshCredentialRequest) (bool, error) {
	var passwordHash, recoveryHash string
	if err := h.DB.SQL.QueryRowContext(ctx, `
		SELECT password_hash, recovery_code_hash FROM users WHERE id = ?
	`, userID).Scan(&passwordHash, &recoveryHash); err != nil {
		return false, err
	}
	verified := false
	if req.Password != "" {
		match, err := auth.VerifyPassword(req.Password, passwordHash)
		verified = err == nil && match
	}
	if !verified && req.RecoveryCode != "" {
		verified = auth.VerifyRecoveryCode(req.RecoveryCode, recoveryHash)
	}
	if !verified && req.Password == "" {
		auth.DummyVerify(req.RecoveryCode)
	}
	return verified, nil
}

// DeleteSynchronizedData permanently removes every user-owned content and usage
// record while retaining the identity and all login credentials. BEGIN IMMEDIATE
// is configured on every DB transaction, so this reset and SyncHandler.Push are
// serialized by SQLite's write lock. The generation changes in the same commit as
// the deletes; a client can observe neither half without the other.
func (h *AuthHandler) DeleteSynchronizedData(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	var req freshCredentialRequest
	if err := decodeLimitedJSON(w, r, 16*1024, &req); err != nil {
		http.Error(w, `{"error":"invalid payload"}`, http.StatusBadRequest)
		return
	}
	if req.Password == "" && req.RecoveryCode == "" {
		http.Error(w, `{"error":"password or recovery code required"}`, http.StatusBadRequest)
		return
	}
	verified, err := h.verifyFreshCredential(r.Context(), authUser.ID, req)
	if err == sql.ErrNoRows {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	if err != nil {
		http.Error(w, `{"error":"failed to load account"}`, http.StatusInternalServerError)
		return
	}
	if !verified {
		http.Error(w, `{"error":"invalid credentials"}`, http.StatusUnauthorized)
		return
	}

	tx, err := h.DB.SQL.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"transaction error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	var generation int64
	if err := tx.QueryRowContext(r.Context(), `
		UPDATE users
		SET data_generation = data_generation + 1,
		    global_stats_opt_in = 0,
		    global_stats_opt_in_at = 0,
		    updated_at = ?
		WHERE id = ?
		RETURNING data_generation
	`, time.Now().UnixMilli(), authUser.ID).Scan(&generation); err != nil {
		http.Error(w, `{"error":"failed to advance data generation"}`, http.StatusInternalServerError)
		return
	}

	for _, table := range []string{
		"subscriptions",
		"favorites",
		"playback_states",
		"listening_sessions",
		"queue_items",
		"per_podcast_settings",
		"history_entries",
		"sync_log",
		"user_sync_cursors",
		"processed_sync_operations",
		"web_push_subscriptions",
	} {
		if _, err := tx.ExecContext(r.Context(), "DELETE FROM "+table+" WHERE user_id = ?", authUser.ID); err != nil {
			http.Error(w, `{"error":"failed to delete synchronized data"}`, http.StatusInternalServerError)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"failed to delete synchronized data"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"data_generation": generation})
}

// Account deletion and data export.
//
// Both are required before the Android app can ship: Google Play obliges an app
// that can create an account to offer deletion from inside the app *and* from a
// publicly reachable URL, and an export is the other half of the same promise.
// They live apart from auth.go because they are destructive/bulk endpoints with
// their own rules — fresh credentials, no partial writes, nothing derived from a
// session that might be stale.

// DeleteAccount removes the account and everything hanging off it.
//
// Every dependent table declares ON DELETE CASCADE against users(id) and the
// connection runs with foreign_keys=ON, so one statement is the whole deletion —
// and a table added later without a cascade fails the accompanying test rather
// than silently leaving rows behind.
func (h *AuthHandler) DeleteAccount(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	var req freshCredentialRequest
	if err := decodeLimitedJSON(w, r, 16*1024, &req); err != nil {
		http.Error(w, `{"error":"invalid payload"}`, http.StatusBadRequest)
		return
	}
	if req.Password == "" && req.RecoveryCode == "" {
		http.Error(w, `{"error":"password or recovery code required"}`, http.StatusBadRequest)
		return
	}

	verified, err := h.verifyFreshCredential(r.Context(), authUser.ID, req)
	if err == sql.ErrNoRows {
		// Already gone. Deleting a deleted account is not an error to the caller.
		clearSessionCookie(w, h.Config.SecureCookies)
		w.WriteHeader(http.StatusNoContent)
		return
	} else if err != nil {
		http.Error(w, `{"error":"failed to load account"}`, http.StatusInternalServerError)
		return
	}

	if !verified {
		http.Error(w, `{"error":"invalid credentials"}`, http.StatusUnauthorized)
		return
	}

	tx, err := h.DB.SQL.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"transaction error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	// Named explicitly rather than left to the cascade: these two are the ones
	// that keep a deleted account *usable* if they survive, so they are removed
	// first and the account row second. The cascade still covers everything else.
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM sessions WHERE user_id = ?", authUser.ID); err != nil {
		http.Error(w, `{"error":"failed to revoke sessions"}`, http.StatusInternalServerError)
		return
	}
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM device_credentials WHERE user_id = ?", authUser.ID); err != nil {
		http.Error(w, `{"error":"failed to revoke device credentials"}`, http.StatusInternalServerError)
		return
	}
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM users WHERE id = ?", authUser.ID); err != nil {
		http.Error(w, `{"error":"failed to delete account"}`, http.StatusInternalServerError)
		return
	}
	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"failed to delete account"}`, http.StatusInternalServerError)
		return
	}

	clearSessionCookie(w, h.Config.SecureCookies)
	w.WriteHeader(http.StatusNoContent)
}

// ExportAccount returns everything stored for the account as one JSON document.
//
// Deliberately never includes password hashes, recovery-code hashes, session
// secrets or device-token hashes: an export is a copy of the listener's data,
// not a copy of the credentials guarding it.
func (h *AuthHandler) ExportAccount(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	export := map[string]any{
		"exported_at":     time.Now().UTC().Format(time.RFC3339),
		"schema":          1,
		"account":         map[string]any{},
		"subscriptions":   []any{},
		"favorites":       []any{},
		"playback_states": []any{},
	}

	var username, role string
	var createdAt int64
	if err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT username, role, created_at FROM users WHERE id = ?
	`, authUser.ID).Scan(&username, &role, &createdAt); err != nil {
		http.Error(w, `{"error":"failed to load account"}`, http.StatusInternalServerError)
		return
	}
	export["account"] = map[string]any{
		"user_id":    authUser.ID,
		"username":   username,
		"role":       role,
		"created_at": createdAt,
	}

	for _, table := range []struct {
		key   string
		query string
	}{
		{"subscriptions", `SELECT podcast_id, created_at FROM subscriptions WHERE user_id = ? ORDER BY created_at`},
		{"favorites", `SELECT episode_id, created_at FROM favorites WHERE user_id = ? ORDER BY created_at`},
	} {
		rows, err := h.DB.SQL.QueryContext(r.Context(), table.query, authUser.ID)
		if err != nil {
			http.Error(w, `{"error":"failed to export account"}`, http.StatusInternalServerError)
			return
		}
		collected := []any{}
		for rows.Next() {
			var id string
			var created int64
			if err := rows.Scan(&id, &created); err != nil {
				rows.Close()
				http.Error(w, `{"error":"failed to export account"}`, http.StatusInternalServerError)
				return
			}
			collected = append(collected, map[string]any{"id": id, "created_at": created})
		}
		rows.Close()
		export[table.key] = collected
	}

	// Playback and listening data is the bulk of it and is reconstructed from the
	// append-only sync log, which is authoritative for the clients as well. One
	// read transaction across all five, so the export is a single consistent
	// moment rather than five snapshots taken as a sync writes underneath it.
	tx, err := h.DB.SQL.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if err != nil {
		http.Error(w, `{"error":"failed to export account"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()
	for _, entity := range []struct{ key, entityType string }{
		{"playback_states", "playback_state"},
		{"listening_sessions", "listening_session"},
		{"settings", "settings"},
		{"podcast_settings", "podcast_settings"},
		{"queue", "queue"},
	} {
		payloads, err := latestSyncPayloads(tx, r.Context(), authUser.ID, entity.entityType)
		if err != nil {
			http.Error(w, `{"error":"failed to export account"}`, http.StatusInternalServerError)
			return
		}
		export[entity.key] = payloads
	}

	filename := "koalacast-export.json"
	if safe := strings.Map(func(char rune) rune {
		if (char >= 'a' && char <= 'z') || (char >= '0' && char <= '9') || char == '-' || char == '_' {
			return char
		}
		return -1
	}, strings.ToLower(username)); safe != "" {
		filename = "koalacast-export-" + safe + ".json"
	}

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Disposition", `attachment; filename="`+filename+`"`)
	w.WriteHeader(http.StatusOK)
	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(export)
}

func clearSessionCookie(w http.ResponseWriter, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name:     "koalacast_session",
		Value:    "",
		Path:     "/",
		Expires:  time.Unix(0, 0),
		MaxAge:   -1,
		HttpOnly: true,
		Secure:   secure,
		SameSite: http.SameSiteLaxMode,
	})
}

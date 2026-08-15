package handlers

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/auth"
	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
)

type AuthHandler struct {
	DB     *db.DB
	Config *config.Config
}

type DeviceLoginRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
	ClientType string `json:"client_type"` // "android" or "web"
}

type RecoveryVerifyRequest struct {
	Username     string `json:"username"`
	RecoveryCode string `json:"recovery_code"`
	NewPassword  string `json:"new_password"`
}

// isUniqueConstraintError reports whether a write lost a race against a UNIQUE
// index rather than failing for an operational reason. The driver is matched by
// message because the SQLite driver in use does not export a typed error.
func isUniqueConstraintError(err error) bool {
	return err != nil && strings.Contains(strings.ToUpper(err.Error()), "UNIQUE CONSTRAINT FAILED")
}

func randomToken() (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw), nil
}

func (h *AuthHandler) IsRegistrationEnabled(r *http.Request) bool {
	// 1. Environment Enforced Override Takes Top Priority
	if h.Config.RegistrationEnabledEnv != nil {
		return *h.Config.RegistrationEnabledEnv
	}

	// 2. Fallback to Database Admin Setting
	var enabledStr string
	err := h.DB.SQL.QueryRowContext(r.Context(), "SELECT value FROM app_settings WHERE key = 'registration_enabled'").Scan(&enabledStr)
	if err == sql.ErrNoRows {
		return true // Default enabled
	} else if err == nil && enabledStr == "false" {
		return false
	}

	return true
}

func (h *AuthHandler) Register(w http.ResponseWriter, r *http.Request) {
	if !h.IsRegistrationEnabled(r) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusForbidden)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": "public registration is disabled on this server"})
		return
	}

	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}

	if err := decodeLimitedJSON(w, r, 16*1024, &req); err != nil {
		http.Error(w, `{"error":"invalid payload"}`, http.StatusBadRequest)
		return
	}

	username := strings.TrimSpace(req.Username)
	normalizedUsername := strings.ToLower(username)

	if len(normalizedUsername) < 3 || len(normalizedUsername) > 32 {
		http.Error(w, `{"error":"username must be between 3 and 32 characters"}`, http.StatusBadRequest)
		return
	}

	if len(req.Password) < 8 {
		http.Error(w, `{"error":"password must be at least 8 characters long"}`, http.StatusBadRequest)
		return
	}

	// Check if normalized username already exists
	var count int
	err := h.DB.SQL.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM users WHERE normalized_username = ?", normalizedUsername).Scan(&count)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	if count > 0 {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": "username is already taken"})
		return
	}

	// Hash Password with Argon2id
	pwdHash, err := auth.HashPassword(req.Password)
	if err != nil {
		http.Error(w, `{"error":"failed to hash password"}`, http.StatusInternalServerError)
		return
	}

	// Generate 32-byte Base32 Recovery Code & Verifier Hash
	recoveryCode, recoveryHash, err := auth.GenerateRecoveryCode()
	if err != nil {
		http.Error(w, `{"error":"failed to generate recovery code"}`, http.StatusInternalServerError)
		return
	}

	userID := uuid.New().String()
	nowMs := time.Now().UnixMilli()

	// Serialize first-user role selection with account creation. OpenDB configures
	// transactions as BEGIN IMMEDIATE, so concurrent registrations cannot both
	// observe an empty users table and both become administrators.
	tx, err := h.DB.SQL.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	var userCount int
	if err := tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM users").Scan(&userCount); err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	role := "user"
	if userCount == 0 && strings.TrimSpace(h.Config.AdminUsername) == "" {
		role = "admin"
	}

	_, err = tx.ExecContext(r.Context(), `
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, role, is_suspended, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
	`, userID, username, normalizedUsername, pwdHash, recoveryHash, role, nowMs, nowMs)
	if err != nil {
		// The availability check above runs outside this transaction, so two
		// simultaneous sign-ups for the same name both pass it and the unique
		// index decides. That is the same "name is taken" answer as before, not a
		// server fault, and telling the loser otherwise sends them to a bug report
		// instead of to a different username.
		if isUniqueConstraintError(err) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusConflict)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "username is already taken"})
			return
		}
		http.Error(w, `{"error":"failed to create user account"}`, http.StatusInternalServerError)
		return
	}
	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"failed to create user account"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"user_id":       userID,
		"username":      username,
		"role":          role,
		"recovery_code": recoveryCode,
		"warning":       "Store your recovery code securely. Loss of both password and recovery code makes account recovery impossible.",
	})
}

func (h *AuthHandler) Login(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}

	if err := decodeLimitedJSON(w, r, 16*1024, &req); err != nil {
		http.Error(w, `{"error":"invalid payload"}`, http.StatusBadRequest)
		return
	}

	normalizedUsername := strings.ToLower(strings.TrimSpace(req.Username))

	var userID, username, pwdHash, role string
	var isSuspended int

	err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT id, username, password_hash, role, is_suspended
		FROM users
		WHERE normalized_username = ?
	`, normalizedUsername).Scan(&userID, &username, &pwdHash, &role, &isSuspended)

	if err == sql.ErrNoRows {
		// Spend the same CPU as a real verification so response time does not
		// reveal whether the username exists.
		auth.DummyVerify(req.Password)
		http.Error(w, `{"error":"invalid username or password"}`, http.StatusUnauthorized)
		return
	} else if err != nil || isSuspended == 1 {
		http.Error(w, `{"error":"invalid username or password"}`, http.StatusUnauthorized)
		return
	}

	// Verify Argon2id Password
	match, err := auth.VerifyPassword(req.Password, pwdHash)
	if err != nil || !match {
		http.Error(w, `{"error":"invalid username or password"}`, http.StatusUnauthorized)
		return
	}

	// Generate Random Session Token
	rawToken, err := randomToken()
	if err != nil {
		http.Error(w, `{"error":"failed to create session token"}`, http.StatusInternalServerError)
		return
	}

	tokenHashBytes := sha256.Sum256([]byte(rawToken))
	tokenHash := hex.EncodeToString(tokenHashBytes[:])

	sessionID := uuid.New().String()
	nowMs := time.Now().UnixMilli()
	expiresAtMs := nowMs + (30 * 86400 * 1000) // 30 days

	// r.RemoteAddr is "host:port"; TruncateIP needs the bare host or it parses to
	// nothing and stores an empty IP. (RealIP already strips the port when the
	// request arrives via a trusted proxy, but a direct connection still has one.)
	truncatedIP := customMiddleware.TruncateIP(customMiddleware.ClientHostIP(r.RemoteAddr))
	sanitizedUA := customMiddleware.SanitizeUserAgent(r.UserAgent())

	_, err = h.DB.SQL.ExecContext(r.Context(), `
		INSERT INTO sessions (id, user_id, token_hash, device_name, device_type, truncated_ip, sanitized_user_agent, expires_at, created_at, last_used_at)
		VALUES (?, ?, ?, ?, 'web', ?, ?, ?, ?, ?)
	`, sessionID, userID, tokenHash, "Web Session", truncatedIP, sanitizedUA, expiresAtMs, nowMs, nowMs)
	if err != nil {
		http.Error(w, `{"error":"failed to create session"}`, http.StatusInternalServerError)
		return
	}

	// Set HttpOnly Same-Origin Session Cookie
	http.SetCookie(w, &http.Cookie{
		Name:     "koalacast_session",
		Value:    rawToken,
		Path:     "/",
		Expires:  time.Unix(expiresAtMs/1000, 0),
		HttpOnly: true,
		Secure:   h.Config.SecureCookies,
		SameSite: http.SameSiteStrictMode,
	})

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"user_id":  userID,
		"username": username,
		"role":     role,
	})
}

func (h *AuthHandler) DeviceLogin(w http.ResponseWriter, r *http.Request) {
	var req DeviceLoginRequest
	if err := decodeLimitedJSON(w, r, 16*1024, &req); err != nil {
		http.Error(w, `{"error":"invalid payload"}`, http.StatusBadRequest)
		return
	}

	normalizedUsername := strings.ToLower(strings.TrimSpace(req.Username))
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	req.DeviceName = strings.TrimSpace(req.DeviceName)
	req.ClientType = strings.ToLower(strings.TrimSpace(req.ClientType))
	if len(req.DeviceID) > 128 || len(req.DeviceName) > 128 ||
		(req.ClientType != "android" && req.ClientType != "web") {
		http.Error(w, `{"error":"invalid device metadata"}`, http.StatusBadRequest)
		return
	}

	var userID, username, pwdHash, role string
	var isSuspended int

	err := h.DB.SQL.QueryRowContext(r.Context(), `
		SELECT id, username, password_hash, role, is_suspended
		FROM users
		WHERE normalized_username = ?
	`, normalizedUsername).Scan(&userID, &username, &pwdHash, &role, &isSuspended)

	if err == sql.ErrNoRows {
		auth.DummyVerify(req.Password)
		http.Error(w, `{"error":"invalid credentials"}`, http.StatusUnauthorized)
		return
	} else if err != nil || isSuspended == 1 {
		http.Error(w, `{"error":"invalid credentials"}`, http.StatusUnauthorized)
		return
	}

	match, err := auth.VerifyPassword(req.Password, pwdHash)
	if err != nil || !match {
		http.Error(w, `{"error":"invalid credentials"}`, http.StatusUnauthorized)
		return
	}

	if req.DeviceID == "" {
		req.DeviceID = uuid.New().String()
	}
	if req.DeviceName == "" {
		req.DeviceName = "Native Client"
	}

	rawToken, err := randomToken()
	if err != nil {
		http.Error(w, `{"error":"failed to create device token"}`, http.StatusInternalServerError)
		return
	}

	tokenHashBytes := sha256.Sum256([]byte(rawToken))
	tokenHash := hex.EncodeToString(tokenHashBytes[:])

	nowMs := time.Now().UnixMilli()

	deviceID := uuid.New().String()
	expiresAtMs := nowMs + (90 * 86400 * 1000) // 90 days
	tx, err := h.DB.SQL.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"failed to issue device credentials"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(r.Context(), "DELETE FROM device_credentials WHERE user_id = ? AND device_id = ?", userID, req.DeviceID); err != nil {
		http.Error(w, `{"error":"failed to issue device credentials"}`, http.StatusInternalServerError)
		return
	}
	_, err = tx.ExecContext(r.Context(), `
		INSERT INTO device_credentials (id, user_id, device_id, name, token_hash, client_type, client_schema_version, created_at, last_sync_at, is_revoked, expires_at)
		VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 0, ?)
	`, deviceID, userID, req.DeviceID, req.DeviceName, tokenHash, req.ClientType, nowMs, nowMs, expiresAtMs)
	if err != nil {
		http.Error(w, `{"error":"failed to issue device credentials"}`, http.StatusInternalServerError)
		return
	}
	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"failed to issue device credentials"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"user_id":      userID,
		"username":     username,
		"device_id":    req.DeviceID,
		"device_token": rawToken,
	})
}

func (h *AuthHandler) Logout(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	var err error
	if authUser != nil && authUser.SessionID != "" {
		// Web client: drop the browser session row.
		_, err = h.DB.SQL.ExecContext(r.Context(), "DELETE FROM sessions WHERE id = ?", authUser.SessionID)
	} else if authUser != nil && authUser.DeviceID != "" {
		// Native client (Bearer device token): revoke this device's credential so
		// the token stops working immediately instead of lingering until expiry.
		_, err = h.DB.SQL.ExecContext(r.Context(),
			"DELETE FROM device_credentials WHERE user_id = ? AND device_id = ?",
			authUser.ID, authUser.DeviceID)
	}
	if err != nil {
		http.Error(w, `{"error":"failed to revoke session"}`, http.StatusInternalServerError)
		return
	}

	// Clear session cookie
	http.SetCookie(w, &http.Cookie{
		Name:     "koalacast_session",
		Value:    "",
		Path:     "/",
		Expires:  time.Unix(0, 0),
		HttpOnly: true,
		Secure:   h.Config.SecureCookies,
		SameSite: http.SameSiteStrictMode,
	})

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{"message": "logged out successfully"})
}

func (h *AuthHandler) Me(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"user_id":     authUser.ID,
		"username":    authUser.Username,
		"role":        authUser.Role,
		"client_type": authUser.ClientType,
	})
}

// Status is the browser's quiet session probe. Unlike /auth/me it is public and
// always returns 200, so an ordinary anonymous page load does not create a
// misleading console error. OptionalAuth still validates any supplied cookie or
// bearer token and attaches the same authenticated user context.
func (h *AuthHandler) Status(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if authUser == nil {
		_ = json.NewEncoder(w).Encode(map[string]bool{"authenticated": false})
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"authenticated": true,
		"user_id":       authUser.ID,
		"username":      authUser.Username,
		"role":          authUser.Role,
		"client_type":   authUser.ClientType,
	})
}

func (h *AuthHandler) ListSessions(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	nowMs := time.Now().UnixMilli()

	type SessionItem struct {
		ID                 string `json:"id"`
		Kind               string `json:"kind"` // "session" (web) | "device" (native)
		DeviceName         string `json:"device_name"`
		DeviceType         string `json:"device_type"`
		TruncatedIP        string `json:"truncated_ip"`
		SanitizedUserAgent string `json:"sanitized_user_agent"`
		CreatedAt          int64  `json:"created_at"`
		LastUsedAt         int64  `json:"last_used_at"`
		IsCurrent          bool   `json:"is_current"`
	}

	sessions := make([]SessionItem, 0)

	// Web browser sessions.
	rows, err := h.DB.SQL.QueryContext(r.Context(), `
		SELECT id, device_name, device_type, truncated_ip, sanitized_user_agent, created_at, last_used_at
		FROM sessions
		WHERE user_id = ? AND expires_at > ?
		ORDER BY last_used_at DESC
	`, authUser.ID, nowMs)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	for rows.Next() {
		var item SessionItem
		if err := rows.Scan(&item.ID, &item.DeviceName, &item.DeviceType, &item.TruncatedIP, &item.SanitizedUserAgent, &item.CreatedAt, &item.LastUsedAt); err == nil {
			item.Kind = "session"
			item.IsCurrent = (item.ID == authUser.SessionID)
			sessions = append(sessions, item)
		}
	}
	rowsErr := rows.Err()
	rows.Close()

	// Native device credentials (Bearer tokens).
	dRows, err := h.DB.SQL.QueryContext(r.Context(), `
		SELECT id, device_id, name, client_type, created_at, last_sync_at
		FROM device_credentials
		WHERE user_id = ? AND is_revoked = 0 AND (expires_at = 0 OR expires_at > ?)
		ORDER BY last_sync_at DESC
	`, authUser.ID, nowMs)
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	for dRows.Next() {
		var item SessionItem
		var deviceID string
		if err := dRows.Scan(&item.ID, &deviceID, &item.DeviceName, &item.DeviceType, &item.CreatedAt, &item.LastUsedAt); err == nil {
			item.Kind = "device"
			// A device credential authenticated the current request when its
			// device_id matches the one the middleware resolved from the token.
			item.IsCurrent = (authUser.SessionID == "" && deviceID == authUser.DeviceID)
			sessions = append(sessions, item)
		}
	}
	dRowsErr := dRows.Err()
	dRows.Close()
	// This list is what a listener uses to spot a session they do not recognise.
	// A truncated read must not quietly present a shorter list as the whole
	// picture — the one session missing from it could be the one that matters.
	if rowsErr != nil || dRowsErr != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"sessions": sessions,
	})
}

func (h *AuthHandler) RevokeSession(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	sessionID := chi.URLParam(r, "id")

	// The id may reference either a web session or a native device credential.
	// Both deletes are scoped to the authenticated user, so only that user's own
	// rows can ever be revoked; exactly one table (if any) will match.
	res, err := h.DB.SQL.ExecContext(r.Context(), "DELETE FROM sessions WHERE id = ? AND user_id = ?", sessionID, authUser.ID)
	if err != nil {
		http.Error(w, `{"error":"failed to revoke session"}`, http.StatusInternalServerError)
		return
	}
	affected, _ := res.RowsAffected()

	if affected == 0 {
		dRes, dErr := h.DB.SQL.ExecContext(r.Context(), "DELETE FROM device_credentials WHERE id = ? AND user_id = ?", sessionID, authUser.ID)
		if dErr != nil {
			http.Error(w, `{"error":"failed to revoke session"}`, http.StatusInternalServerError)
			return
		}
		affected, _ = dRes.RowsAffected()
	}

	if affected == 0 {
		http.Error(w, `{"error":"session not found"}`, http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{"message": "session revoked"})
}

func (h *AuthHandler) VerifyRecoveryCode(w http.ResponseWriter, r *http.Request) {
	var req RecoveryVerifyRequest
	if err := decodeLimitedJSON(w, r, 16*1024, &req); err != nil {
		http.Error(w, `{"error":"invalid payload"}`, http.StatusBadRequest)
		return
	}

	normalizedUsername := strings.ToLower(strings.TrimSpace(req.Username))
	if len(req.NewPassword) < 8 {
		http.Error(w, `{"error":"new password must be at least 8 characters long"}`, http.StatusBadRequest)
		return
	}

	var userID, storedRecoveryHash string
	err := h.DB.SQL.QueryRowContext(r.Context(), "SELECT id, recovery_code_hash FROM users WHERE normalized_username = ?", normalizedUsername).Scan(&userID, &storedRecoveryHash)
	if err == sql.ErrNoRows {
		// Spend the same work as a real verification, exactly as Login does, so
		// this endpoint does not become the cheap way to learn which usernames
		// exist on an instance.
		auth.DummyVerify(req.RecoveryCode)
		http.Error(w, `{"error":"invalid recovery code or username"}`, http.StatusUnauthorized)
		return
	} else if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}

	if !auth.VerifyRecoveryCode(req.RecoveryCode, storedRecoveryHash) {
		http.Error(w, `{"error":"invalid recovery code or username"}`, http.StatusUnauthorized)
		return
	}

	// Password reset approved -> Hash new password & regenerate recovery code
	newPwdHash, err := auth.HashPassword(req.NewPassword)
	if err != nil {
		http.Error(w, `{"error":"failed to hash password"}`, http.StatusInternalServerError)
		return
	}
	newRecoveryCode, newRecoveryHash, err := auth.GenerateRecoveryCode()
	if err != nil {
		http.Error(w, `{"error":"failed to generate recovery code"}`, http.StatusInternalServerError)
		return
	}

	nowMs := time.Now().UnixMilli()

	tx, err := h.DB.SQL.BeginTx(r.Context(), nil)
	if err != nil {
		http.Error(w, `{"error":"transaction error"}`, http.StatusInternalServerError)
		return
	}
	defer tx.Rollback()

	_, err = tx.ExecContext(r.Context(), `
		UPDATE users
		SET password_hash = ?,
			recovery_code_hash = ?,
			updated_at = ?
		WHERE id = ?
	`, newPwdHash, newRecoveryHash, nowMs, userID)
	if err != nil {
		http.Error(w, `{"error":"failed to reset password"}`, http.StatusInternalServerError)
		return
	}

	if _, err := tx.ExecContext(r.Context(), "DELETE FROM sessions WHERE user_id = ?", userID); err != nil {
		http.Error(w, `{"error":"failed to revoke sessions"}`, http.StatusInternalServerError)
		return
	}
	if _, err := tx.ExecContext(r.Context(), "DELETE FROM device_credentials WHERE user_id = ?", userID); err != nil {
		http.Error(w, `{"error":"failed to revoke device credentials"}`, http.StatusInternalServerError)
		return
	}
	if err := tx.Commit(); err != nil {
		http.Error(w, `{"error":"failed to reset password"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"message":           "password reset successful",
		"new_recovery_code": newRecoveryCode,
	})
}

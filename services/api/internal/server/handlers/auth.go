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

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
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

	// Check if this is the first user -> Seed as Admin
	var userCount int
	_ = h.DB.SQL.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM users").Scan(&userCount)
	role := "user"
	if userCount == 0 {
		role = "admin"
	}

	_, err = h.DB.SQL.ExecContext(r.Context(), `
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, role, is_suspended, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
	`, userID, username, normalizedUsername, pwdHash, recoveryHash, role, nowMs, nowMs)
	if err != nil {
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

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
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
		http.Error(w, `{"error":"invalid username or password"}`, http.StatusUnauthorized)
		return
	} else if err != nil || isSuspended == 1 {
		http.Error(w, `{"error":"account suspended or database error"}`, http.StatusUnauthorized)
		return
	}

	// Verify Argon2id Password
	match, err := auth.VerifyPassword(req.Password, pwdHash)
	if err != nil || !match {
		http.Error(w, `{"error":"invalid username or password"}`, http.StatusUnauthorized)
		return
	}

	// Generate Random Session Token
	rawTokenBytes := make([]byte, 32)
	_, _ = rand.Read(rawTokenBytes)
	rawToken := hex.EncodeToString(rawTokenBytes)

	tokenHashBytes := sha256.Sum256([]byte(rawToken))
	tokenHash := hex.EncodeToString(tokenHashBytes[:])

	sessionID := uuid.New().String()
	nowMs := time.Now().UnixMilli()
	expiresAtMs := nowMs + (30 * 86400 * 1000) // 30 days

	truncatedIP := customMiddleware.TruncateIP(r.RemoteAddr)
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
		SameSite: http.SameSiteLaxMode,
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
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
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

	if err == sql.ErrNoRows || isSuspended == 1 {
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

	rawTokenBytes := make([]byte, 32)
	_, _ = rand.Read(rawTokenBytes)
	rawToken := hex.EncodeToString(rawTokenBytes)

	tokenHashBytes := sha256.Sum256([]byte(rawToken))
	tokenHash := hex.EncodeToString(tokenHashBytes[:])

	nowMs := time.Now().UnixMilli()

	// Revoke existing device token if present for this device_id
	_, _ = h.DB.SQL.ExecContext(r.Context(), "DELETE FROM device_credentials WHERE user_id = ? AND device_id = ?", userID, req.DeviceID)

	deviceID := uuid.New().String()
	_, err = h.DB.SQL.ExecContext(r.Context(), `
		INSERT INTO device_credentials (id, user_id, device_id, name, token_hash, client_type, client_schema_version, created_at, last_sync_at, is_revoked)
		VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 0)
	`, deviceID, userID, req.DeviceID, req.DeviceName, tokenHash, req.ClientType, nowMs, nowMs)
	if err != nil {
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
	if authUser != nil && authUser.SessionID != "" {
		_, _ = h.DB.SQL.ExecContext(r.Context(), "DELETE FROM sessions WHERE id = ?", authUser.SessionID)
	}

	// Clear session cookie
	http.SetCookie(w, &http.Cookie{
		Name:     "koalacast_session",
		Value:    "",
		Path:     "/",
		Expires:  time.Unix(0, 0),
		HttpOnly: true,
		Secure:   h.Config.SecureCookies,
		SameSite: http.SameSiteLaxMode,
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

func (h *AuthHandler) ListSessions(w http.ResponseWriter, r *http.Request) {
	authUser := customMiddleware.GetAuthUser(r.Context())
	if authUser == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	rows, err := h.DB.SQL.QueryContext(r.Context(), `
		SELECT id, device_name, device_type, truncated_ip, sanitized_user_agent, created_at, last_used_at
		FROM sessions
		WHERE user_id = ? AND expires_at > ?
		ORDER BY last_used_at DESC
	`, authUser.ID, time.Now().UnixMilli())
	if err != nil {
		http.Error(w, `{"error":"database error"}`, http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type SessionItem struct {
		ID                 string `json:"id"`
		DeviceName         string `json:"device_name"`
		DeviceType         string `json:"device_type"`
		TruncatedIP        string `json:"truncated_ip"`
		SanitizedUserAgent string `json:"sanitized_user_agent"`
		CreatedAt          int64  `json:"created_at"`
		LastUsedAt         int64  `json:"last_used_at"`
		IsCurrent          bool   `json:"is_current"`
	}

	sessions := make([]SessionItem, 0)
	for rows.Next() {
		var item SessionItem
		if err := rows.Scan(&item.ID, &item.DeviceName, &item.DeviceType, &item.TruncatedIP, &item.SanitizedUserAgent, &item.CreatedAt, &item.LastUsedAt); err == nil {
			item.IsCurrent = (item.ID == authUser.SessionID)
			sessions = append(sessions, item)
		}
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
	_, err := h.DB.SQL.ExecContext(r.Context(), "DELETE FROM sessions WHERE id = ? AND user_id = ?", sessionID, authUser.ID)
	if err != nil {
		http.Error(w, `{"error":"failed to revoke session"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{"message": "session revoked"})
}

func (h *AuthHandler) VerifyRecoveryCode(w http.ResponseWriter, r *http.Request) {
	var req RecoveryVerifyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
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
		http.Error(w, `{"error":"invalid recovery code or username"}`, http.StatusUnauthorized)
		return
	}

	if !auth.VerifyRecoveryCode(req.RecoveryCode, storedRecoveryHash) {
		http.Error(w, `{"error":"invalid recovery code or username"}`, http.StatusUnauthorized)
		return
	}

	// Password reset approved -> Hash new password & regenerate recovery code
	newPwdHash, _ := auth.HashPassword(req.NewPassword)
	newRecoveryCode, newRecoveryHash, _ := auth.GenerateRecoveryCode()

	nowMs := time.Now().UnixMilli()

	_, err = h.DB.SQL.ExecContext(r.Context(), `
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

	// Revoke all existing sessions upon recovery reset
	_, _ = h.DB.SQL.ExecContext(r.Context(), "DELETE FROM sessions WHERE user_id = ?", userID)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"message":           "password reset successful",
		"new_recovery_code": newRecoveryCode,
	})
}

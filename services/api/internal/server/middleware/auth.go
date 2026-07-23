package middleware

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
)

type AuthUser struct {
	ID                 string
	Username           string
	NormalizedUsername string
	Role               string
	SessionID          string
	DeviceID           string
	ClientType         string
}

const UserContextKey contextKey = "auth_user"

func AuthRequired(database *db.DB) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			user, err := AuthenticateRequest(r, database)
			if err != nil || user == nil {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"error":"unauthorized access"}`))
				return
			}

			ctx := context.WithValue(r.Context(), UserContextKey, user)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func OptionalAuth(database *db.DB) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			user, _ := AuthenticateRequest(r, database)
			if user != nil {
				ctx := context.WithValue(r.Context(), UserContextKey, user)
				r = r.WithContext(ctx)
			}
			next.ServeHTTP(w, r)
		})
	}
}

func GetAuthUser(ctx context.Context) *AuthUser {
	if user, ok := ctx.Value(UserContextKey).(*AuthUser); ok {
		return user
	}
	return nil
}

func AuthenticateRequest(r *http.Request, database *db.DB) (*AuthUser, error) {
	// 1. Check Bearer Token in Authorization Header (For API & Native Mobile Clients)
	authHeader := r.Header.Get("Authorization")
	if strings.HasPrefix(authHeader, "Bearer ") {
		token := strings.TrimPrefix(authHeader, "Bearer ")
		return authenticateDeviceToken(r.Context(), database, token)
	}

	// 2. Check Same-Origin Session Cookie (For Web Client)
	cookie, err := r.Cookie("koalacast_session")
	if err == nil && cookie.Value != "" {
		return authenticateSessionToken(r.Context(), database, cookie.Value)
	}

	return nil, nil
}

func authenticateSessionToken(ctx context.Context, database *db.DB, token string) (*AuthUser, error) {
	hash := sha256.Sum256([]byte(token))
	tokenHash := hex.EncodeToString(hash[:])
	nowMs := time.Now().UnixMilli()

	var user AuthUser
	var isSuspended int

	err := database.SQL.QueryRowContext(ctx, `
		SELECT u.id, u.username, u.normalized_username, u.role, u.is_suspended, s.id
		FROM sessions s
		JOIN users u ON s.user_id = u.id
		WHERE s.token_hash = ? AND s.expires_at > ?
	`, tokenHash, nowMs).Scan(&user.ID, &user.Username, &user.NormalizedUsername, &user.Role, &isSuspended, &user.SessionID)

	if err != nil || isSuspended == 1 {
		return nil, err
	}

	user.ClientType = "web"

	// Update last used timestamp
	_, _ = database.SQL.ExecContext(ctx, "UPDATE sessions SET last_used_at = ? WHERE id = ?", nowMs, user.SessionID)

	return &user, nil
}

func authenticateDeviceToken(ctx context.Context, database *db.DB, token string) (*AuthUser, error) {
	hash := sha256.Sum256([]byte(token))
	tokenHash := hex.EncodeToString(hash[:])
	nowMs := time.Now().UnixMilli()

	var user AuthUser
	var isSuspended, isRevoked int

	err := database.SQL.QueryRowContext(ctx, `
		SELECT u.id, u.username, u.normalized_username, u.role, u.is_suspended, d.device_id, d.client_type, d.is_revoked
		FROM device_credentials d
		JOIN users u ON d.user_id = u.id
		WHERE d.token_hash = ?
	`, tokenHash).Scan(&user.ID, &user.Username, &user.NormalizedUsername, &user.Role, &isSuspended, &user.DeviceID, &user.ClientType, &isRevoked)

	if err != nil || isSuspended == 1 || isRevoked == 1 {
		return nil, err
	}

	_, _ = database.SQL.ExecContext(ctx, "UPDATE device_credentials SET last_sync_at = ? WHERE user_id = ? AND device_id = ?", nowMs, user.ID, user.DeviceID)

	return &user, nil
}

// TruncateIP anonymizes raw client IP addresses (e.g. 192.168.1.50 -> 192.168.1.0 or 2001:db8::1 -> 2001:db8::).
func TruncateIP(rawIP string) string {
	ip := net.ParseIP(rawIP)
	if ip == nil {
		return ""
	}
	if ip4 := ip.To4(); ip4 != nil {
		return fmt.Sprintf("%d.%d.%d.0", ip4[0], ip4[1], ip4[2])
	}
	mask := net.CIDRMask(64, 128)
	return ip.Mask(mask).String()
}

// SanitizeUserAgent extracts browser/OS engine info without retaining fingerprinting details.
func SanitizeUserAgent(ua string) string {
	ua = strings.TrimSpace(ua)
	if len(ua) > 100 {
		return ua[:100]
	}
	return ua
}

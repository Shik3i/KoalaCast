package middleware

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io/ioutil"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
)

func newTestDB(t *testing.T) *db.DB {
	t.Helper()
	tempDir, err := ioutil.TempDir("", "koala_auth_mw_*")
	if err != nil {
		t.Fatalf("temp dir: %v", err)
	}
	t.Cleanup(func() { os.RemoveAll(tempDir) })
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(tempDir, "test.db"), logger)
	if err != nil {
		t.Fatalf("OpenDB: %v", err)
	}
	t.Cleanup(func() { database.Close() })
	return database
}

func seedUserAndDeviceToken(t *testing.T, database *db.DB, token string, expiresAt int64) {
	t.Helper()
	ctx := context.Background()
	now := time.Now().UnixMilli()
	_, err := database.SQL.ExecContext(ctx, `
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, role, is_suspended, created_at, updated_at)
		VALUES ('u1','User','user','x','y','user',0,?,?)`, now, now)
	if err != nil {
		t.Fatalf("insert user: %v", err)
	}
	hash := sha256.Sum256([]byte(token))
	_, err = database.SQL.ExecContext(ctx, `
		INSERT INTO device_credentials (id, user_id, device_id, name, token_hash, client_type, client_schema_version, created_at, last_sync_at, is_revoked, expires_at)
		VALUES ('d1','u1','dev-1','Dev',?,'android',1,?,?,0,?)`,
		hex.EncodeToString(hash[:]), now, now, expiresAt)
	if err != nil {
		t.Fatalf("insert device credential: %v", err)
	}
}

func requestWithBearer(token string) *http.Request {
	req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/me", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	return req
}

func TestDeviceToken_ValidWhenNotExpired(t *testing.T) {
	database := newTestDB(t)
	token := "valid-token-123"
	seedUserAndDeviceToken(t, database, token, time.Now().UnixMilli()+3600_000)

	user, err := AuthenticateRequest(requestWithBearer(token), database)
	if err != nil || user == nil {
		t.Fatalf("expected valid device token to authenticate, got user=%v err=%v", user, err)
	}
}

func TestDeviceToken_RejectedWhenExpired(t *testing.T) {
	database := newTestDB(t)
	token := "expired-token-123"
	seedUserAndDeviceToken(t, database, token, time.Now().UnixMilli()-1000)

	user, _ := AuthenticateRequest(requestWithBearer(token), database)
	if user != nil {
		t.Errorf("expected expired device token to be rejected, got user=%v", user)
	}
}

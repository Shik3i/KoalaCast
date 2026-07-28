package handlers

import (
	"bytes"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/auth"
	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
)

func newAuthTestHandler(t *testing.T) (*AuthHandler, *db.DB) {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "auth.db"), logger)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.Close() })
	return &AuthHandler{DB: database, Config: &config.Config{}}, database
}

func TestRecoveryResetRevokesWebAndDeviceCredentials(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	recoveryCode := "ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ23-4567"
	if _, err := database.SQL.Exec(`
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at)
		VALUES ('u1','User','user','old',?,0,0);
		INSERT INTO sessions (id,user_id,token_hash,expires_at,created_at,last_used_at)
		VALUES ('s1','u1','session-hash',9999999999999,0,0);
		INSERT INTO device_credentials (id,user_id,device_id,token_hash,created_at,last_sync_at,expires_at)
		VALUES ('d1','u1','device-1','device-hash',0,0,9999999999999)
	`, auth.HashRecoveryCode(recoveryCode)); err != nil {
		t.Fatal(err)
	}

	body := `{"username":"user","recovery_code":"` + recoveryCode + `","new_password":"NewPassword123!"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/recovery/verify", bytes.NewBufferString(body))
	rec := httptest.NewRecorder()
	handler.VerifyRecoveryCode(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("reset: %d %s", rec.Code, rec.Body.String())
	}
	for _, table := range []string{"sessions", "device_credentials"} {
		var count int
		if err := database.SQL.QueryRow("SELECT COUNT(*) FROM " + table + " WHERE user_id='u1'").Scan(&count); err != nil || count != 0 {
			t.Fatalf("%s not revoked: count=%d err=%v", table, count, err)
		}
	}
}

func TestConcurrentFirstRegistrationsCreateExactlyOneAdmin(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	const registrations = 4
	start := make(chan struct{})
	var wait sync.WaitGroup
	for i := 0; i < registrations; i++ {
		wait.Add(1)
		go func(index int) {
			defer wait.Done()
			<-start
			body := []byte(`{"username":"user` + string(rune('a'+index)) + `","password":"Password123!"}`)
			req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewReader(body))
			rec := httptest.NewRecorder()
			handler.Register(rec, req)
			if rec.Code != http.StatusCreated {
				t.Errorf("registration %d: %d %s", index, rec.Code, rec.Body.String())
			}
		}(i)
	}
	close(start)
	wait.Wait()

	var admins int
	if err := database.SQL.QueryRow(`SELECT COUNT(*) FROM users WHERE role='admin'`).Scan(&admins); err != nil {
		t.Fatal(err)
	}
	if admins != 1 {
		t.Fatalf("expected exactly one bootstrap admin, got %d", admins)
	}
}

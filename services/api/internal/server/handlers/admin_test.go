package handlers

import (
	"bytes"
	"context"
	"io/ioutil"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
	"github.com/go-chi/chi/v5"
)

func TestAdminHandler_RequireAdmin_Forbidden(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_admin_test_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	dbPath := filepath.Join(tempDir, "test.db")
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))

	database, err := db.OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	handler := &AdminHandler{
		DB:     database,
		Config: &config.Config{},
	}

	nextHandler := handler.RequireAdmin(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	// Case 1: Unauthenticated request -> 403 Forbidden
	req1 := httptest.NewRequest(http.MethodGet, "/api/v1/admin/users", nil)
	rec1 := httptest.NewRecorder()
	nextHandler.ServeHTTP(rec1, req1)

	if rec1.Code != http.StatusForbidden {
		t.Errorf("expected 403 Forbidden for unauthenticated request, got %d", rec1.Code)
	}

	// Case 2: Regular user request -> 403 Forbidden
	ctx := context.WithValue(context.Background(), customMiddleware.UserContextKey, &customMiddleware.AuthUser{
		ID:       "user-reg",
		Username: "regularuser",
		Role:     "user",
	})
	req2 := httptest.NewRequest(http.MethodGet, "/api/v1/admin/users", nil).WithContext(ctx)
	rec2 := httptest.NewRecorder()
	nextHandler.ServeHTTP(rec2, req2)

	if rec2.Code != http.StatusForbidden {
		t.Errorf("expected 403 Forbidden for regular user, got %d", rec2.Code)
	}

	// Case 3: Admin user request -> 200 OK
	ctxAdmin := context.WithValue(context.Background(), customMiddleware.UserContextKey, &customMiddleware.AuthUser{
		ID:       "admin-user",
		Username: "admin",
		Role:     "admin",
	})
	req3 := httptest.NewRequest(http.MethodGet, "/api/v1/admin/users", nil).WithContext(ctxAdmin)
	rec3 := httptest.NewRecorder()
	nextHandler.ServeHTTP(rec3, req3)

	if rec3.Code != http.StatusOK {
		t.Errorf("expected 200 OK for admin user, got %d", rec3.Code)
	}
}

func TestSuspendUserRejectsMalformedPayloadAndRevokesAllCredentials(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "admin-suspend.db"), logger)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	if _, err := database.SQL.Exec(`
		INSERT INTO users
			(id,username,normalized_username,password_hash,recovery_code_hash,role,is_suspended,created_at,updated_at)
		VALUES
			('admin','Admin','admin','x','y','admin',0,0,0),
			('target','Target','target','x','y','user',0,0,0);
		INSERT INTO sessions (id,user_id,token_hash,expires_at,created_at,last_used_at)
		VALUES ('session','target','session-hash',9999999999999,0,0);
		INSERT INTO device_credentials (id,user_id,device_id,token_hash,created_at,last_sync_at,expires_at)
		VALUES ('device','target','phone','device-hash',0,0,9999999999999)
	`); err != nil {
		t.Fatal(err)
	}
	handler := &AdminHandler{DB: database, Config: &config.Config{}}
	request := func(body string) *httptest.ResponseRecorder {
		routeCtx := chi.NewRouteContext()
		routeCtx.URLParams.Add("id", "target")
		ctx := context.WithValue(context.Background(), chi.RouteCtxKey, routeCtx)
		ctx = context.WithValue(ctx, customMiddleware.UserContextKey, &customMiddleware.AuthUser{
			ID: "admin", Role: "admin",
		})
		req := httptest.NewRequest(http.MethodPost, "/api/v1/admin/users/target/suspend", bytes.NewBufferString(body)).
			WithContext(ctx)
		rec := httptest.NewRecorder()
		handler.SuspendUser(rec, req)
		return rec
	}

	if rec := request(`{"suspend":`); rec.Code != http.StatusBadRequest {
		t.Fatalf("malformed payload: expected 400, got %d", rec.Code)
	}
	var suspended int
	if err := database.SQL.QueryRow(`SELECT is_suspended FROM users WHERE id='target'`).Scan(&suspended); err != nil || suspended != 0 {
		t.Fatalf("malformed request changed state: suspended=%d err=%v", suspended, err)
	}
	if rec := request(`{"suspend":true}`); rec.Code != http.StatusOK {
		t.Fatalf("suspend: %d %s", rec.Code, rec.Body.String())
	}
	for _, table := range []string{"sessions", "device_credentials"} {
		var count int
		if err := database.SQL.QueryRow("SELECT COUNT(*) FROM " + table + " WHERE user_id='target'").Scan(&count); err != nil || count != 0 {
			t.Fatalf("%s not revoked: count=%d err=%v", table, count, err)
		}
	}
}

func TestAdminHandler_ToggleRegistration_EnvOverrideBlocked(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_admin_reg_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	dbPath := filepath.Join(tempDir, "test.db")
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))

	database, err := db.OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	trueVal := true
	cfg := &config.Config{
		RegistrationEnabledEnv: &trueVal,
	}

	handler := &AdminHandler{
		DB:     database,
		Config: cfg,
	}

	reqBody := []byte(`{"enabled":false}`)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/admin/registration/toggle", bytes.NewBuffer(reqBody))
	rec := httptest.NewRecorder()

	handler.ToggleRegistration(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Errorf("expected status 400 when attempting to override environment-enforced registration setting, got %d", rec.Code)
	}
}

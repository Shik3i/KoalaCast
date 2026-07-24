package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"log/slog"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/worker"
)

// Exercises the native (mobile) auth surface: device login → Bearer auth →
// listing the device among sessions → revoking it → logout revocation.
func TestDeviceTokenAuthFlow(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "koala_device_test_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	dbPath := filepath.Join(tempDir, "device_test.db")
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))

	database, err := db.OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	cfg := &config.Config{
		Port:                 "8080",
		DatabasePath:         dbPath,
		SessionSecret:        "device-test-secret-with-at-least-32-characters!!",
		FeedMaxResponseBytes: 10485760,
	}
	router := NewRouter(cfg, database, worker.NewFeedWorker(database, cfg, logger), logger)

	// Register a user (first user becomes admin — irrelevant here).
	reqReg := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register",
		bytes.NewBufferString(`{"username":"MobileUser","password":"Password123!"}`))
	recReg := httptest.NewRecorder()
	router.ServeHTTP(recReg, reqReg)
	if recReg.Code != http.StatusCreated {
		t.Fatalf("register: expected 201, got %d: %s", recReg.Code, recReg.Body.String())
	}

	// Device login → device token (no cookie).
	reqDev := httptest.NewRequest(http.MethodPost, "/api/v1/auth/device/login",
		bytes.NewBufferString(`{"username":"mobileuser","password":"Password123!","device_name":"Pixel 9","client_type":"android"}`))
	recDev := httptest.NewRecorder()
	router.ServeHTTP(recDev, reqDev)
	if recDev.Code != http.StatusOK {
		t.Fatalf("device login: expected 200, got %d: %s", recDev.Code, recDev.Body.String())
	}
	var devResp map[string]any
	_ = json.NewDecoder(recDev.Body).Decode(&devResp)
	token, _ := devResp["device_token"].(string)
	if token == "" {
		t.Fatalf("expected non-empty device_token")
	}

	bearer := func(method, path string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, nil)
		req.Header.Set("Authorization", "Bearer "+token)
		rec := httptest.NewRecorder()
		router.ServeHTTP(rec, req)
		return rec
	}

	// Bearer token authenticates /auth/me.
	if rec := bearer(http.MethodGet, "/api/v1/auth/me"); rec.Code != http.StatusOK {
		t.Fatalf("me via bearer: expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	// Sessions list includes this device, flagged current.
	recSess := bearer(http.MethodGet, "/api/v1/auth/sessions")
	if recSess.Code != http.StatusOK {
		t.Fatalf("sessions: expected 200, got %d", recSess.Code)
	}
	var sessResp struct {
		Sessions []struct {
			ID        string `json:"id"`
			Kind      string `json:"kind"`
			IsCurrent bool   `json:"is_current"`
		} `json:"sessions"`
	}
	_ = json.NewDecoder(recSess.Body).Decode(&sessResp)

	var deviceRowID string
	for _, s := range sessResp.Sessions {
		if s.Kind == "device" {
			deviceRowID = s.ID
			if !s.IsCurrent {
				t.Errorf("expected the device session to be marked current")
			}
		}
	}
	if deviceRowID == "" {
		t.Fatalf("expected a device-kind session in the list, got %+v", sessResp.Sessions)
	}

	// Revoking the device credential invalidates the token immediately.
	if rec := bearer(http.MethodDelete, "/api/v1/auth/sessions/"+deviceRowID); rec.Code != http.StatusOK {
		t.Fatalf("revoke device: expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	if rec := bearer(http.MethodGet, "/api/v1/auth/me"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("after revoke: expected 401, got %d", rec.Code)
	}

	// A fresh device login followed by logout must also revoke the token.
	reqDev2 := httptest.NewRequest(http.MethodPost, "/api/v1/auth/device/login",
		bytes.NewBufferString(`{"username":"mobileuser","password":"Password123!","device_name":"Pixel 9","client_type":"android"}`))
	recDev2 := httptest.NewRecorder()
	router.ServeHTTP(recDev2, reqDev2)
	var devResp2 map[string]any
	_ = json.NewDecoder(recDev2.Body).Decode(&devResp2)
	token, _ = devResp2["device_token"].(string)
	if token == "" {
		t.Fatalf("expected non-empty device_token on second login")
	}
	if rec := bearer(http.MethodPost, "/api/v1/auth/logout"); rec.Code != http.StatusOK {
		t.Fatalf("logout: expected 200, got %d", rec.Code)
	}
	if rec := bearer(http.MethodGet, "/api/v1/auth/me"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("after logout: expected 401, got %d", rec.Code)
	}
}

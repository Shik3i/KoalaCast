package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/worker"
)

func TestFullE2E_UserRegistrationLoginSyncAndAdminFlow(t *testing.T) {
	tempDir, err := ioutil.TempDir("", "koala_e2e_test_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	dbPath := filepath.Join(tempDir, "e2e_test.db")
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))

	database, err := db.OpenDB(dbPath, logger)
	if err != nil {
		t.Fatalf("OpenDB failed: %v", err)
	}
	defer database.Close()

	cfg := &config.Config{
		Port:                 "8080",
		DatabasePath:         dbPath,
		SessionSecret:        "e2e-test-secret-with-at-least-32-characters-length",
		FeedMaxResponseBytes: 10485760,
	}

	feedWorker := worker.NewFeedWorker(database, cfg, logger)
	router := NewRouter(cfg, database, feedWorker, logger)

	// ==========================================
	// 1. User Registration (First User -> Admin)
	// ==========================================
	adminRegBody := []byte(`{"username":"AdminUser","password":"Password123!"}`)
	reqReg := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewBuffer(adminRegBody))
	recReg := httptest.NewRecorder()

	router.ServeHTTP(recReg, reqReg)

	if recReg.Code != http.StatusCreated {
		t.Fatalf("expected 201 Created for initial registration, got %d: %s", recReg.Code, recReg.Body.String())
	}

	var regResp map[string]interface{}
	_ = json.NewDecoder(recReg.Body).Decode(&regResp)

	if regResp["role"] != "admin" {
		t.Errorf("expected first registered user to receive admin role, got %v", regResp["role"])
	}
	recoveryCode, _ := regResp["recovery_code"].(string)
	if len(recoveryCode) == 0 {
		t.Fatalf("expected non-empty recovery code in registration response")
	}

	// ==========================================
	// 2. User Login & Session Cookie Retrieval
	// ==========================================
	loginBody := []byte(`{"username":"adminuser","password":"Password123!"}`)
	reqLogin := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", bytes.NewBuffer(loginBody))
	recLogin := httptest.NewRecorder()

	router.ServeHTTP(recLogin, reqLogin)

	if recLogin.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for login, got %d", recLogin.Code)
	}

	cookies := recLogin.Result().Cookies()
	var sessionCookie *http.Cookie
	for _, c := range cookies {
		if c.Name == "koalacast_session" {
			sessionCookie = c
			break
		}
	}
	if sessionCookie == nil || sessionCookie.Value == "" {
		t.Fatalf("expected koalacast_session cookie to be set upon login")
	}

	// ==========================================
	// 3. Authenticated Profile Probe (/api/v1/auth/me)
	// ==========================================
	reqMe := httptest.NewRequest(http.MethodGet, "/api/v1/auth/me", nil)
	reqMe.AddCookie(sessionCookie)
	recMe := httptest.NewRecorder()

	router.ServeHTTP(recMe, reqMe)

	if recMe.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for /api/v1/auth/me, got %d", recMe.Code)
	}

	var meResp map[string]interface{}
	_ = json.NewDecoder(recMe.Body).Decode(&meResp)
	if meResp["username"] != "AdminUser" {
		t.Errorf("expected username AdminUser, got %v", meResp["username"])
	}

	// ==========================================
	// 4. Cross-Device Sync Push Operations
	// ==========================================
	nowMs := time.Now().UnixMilli()
	syncPushPayload := fmt.Sprintf(`{
		"client_schema_version": 1,
		"operations": [
			{
				"client_op_id": "op-001",
				"device_id": "web-browser-1",
				"entity_type": "playback_state",
				"action": "upsert",
				"entity_id": "ep-100",
				"payload": {
					"episode_id": "ep-100",
					"position_ms": 45000,
					"completed": false,
					"progress_percent": 15.0,
					"event_type": "PROGRESS_TICK",
					"playback_session_id": "sess-abc",
					"device_id": "web-browser-1",
					"per_session_seq": 1,
					"client_timestamp": %d
				},
				"client_timestamp": %d
			}
		]
	}`, nowMs, nowMs)

	reqPush := httptest.NewRequest(http.MethodPost, "/api/v1/sync", bytes.NewBufferString(syncPushPayload))
	reqPush.AddCookie(sessionCookie)
	recPush := httptest.NewRecorder()

	router.ServeHTTP(recPush, reqPush)

	if recPush.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for sync push, got %d: %s", recPush.Code, recPush.Body.String())
	}

	var pushResp map[string]interface{}
	_ = json.NewDecoder(recPush.Body).Decode(&pushResp)
	if pushResp["applied_ops"].(float64) != 1 {
		t.Errorf("expected 1 applied operation, got %v", pushResp["applied_ops"])
	}

	// ==========================================
	// 5. Cross-Device Sync Pull Operations
	// ==========================================
	reqPull := httptest.NewRequest(http.MethodGet, "/api/v1/sync?since_cursor=0", nil)
	reqPull.AddCookie(sessionCookie)
	recPull := httptest.NewRecorder()

	router.ServeHTTP(recPull, reqPull)

	if recPull.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for sync pull, got %d", recPull.Code)
	}

	var pullResp map[string]interface{}
	_ = json.NewDecoder(recPull.Body).Decode(&pullResp)
	changesets, ok := pullResp["changesets"].([]interface{})
	if !ok || len(changesets) != 1 {
		t.Fatalf("expected 1 changeset in sync pull, got %v", pullResp["changesets"])
	}

	// ==========================================
	// 6. Admin System Status Probe
	// ==========================================
	reqAdmin := httptest.NewRequest(http.MethodGet, "/api/v1/admin/status", nil)
	reqAdmin.AddCookie(sessionCookie)
	recAdmin := httptest.NewRecorder()

	router.ServeHTTP(recAdmin, reqAdmin)

	if recAdmin.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for admin status, got %d", recAdmin.Code)
	}

	var adminResp map[string]interface{}
	_ = json.NewDecoder(recAdmin.Body).Decode(&adminResp)
	if adminResp["user_count"].(float64) != 1 {
		t.Errorf("expected 1 registered user in system status, got %v", adminResp["user_count"])
	}
}

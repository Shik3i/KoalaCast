package handlers

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
)

func newInstanceHandler(t *testing.T, cfg *config.Config) (*InstanceHandler, *db.DB) {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError}))
	database, err := db.OpenDB(filepath.Join(t.TempDir(), "instance.db"), logger)
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })
	return &InstanceHandler{Config: cfg, Auth: &AuthHandler{DB: database, Config: cfg}}, database
}

func getInstance(t *testing.T, h *InstanceHandler, headers map[string]string) map[string]any {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/instance", nil)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.Get(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	return body
}

func TestInstanceReportsConfiguredIdentity(t *testing.T) {
	h, _ := newInstanceHandler(t, &config.Config{
		ServiceName:      "My Cast",
		OperatorName:     "Example e.V.",
		PrivacyPolicyURL: "https://example.org/datenschutz",
		LegalNoticeURL:   "https://example.org/impressum",
	})
	body := getInstance(t, h, nil)
	want := map[string]any{
		"service_name":         "My Cast",
		"operator_name":        "Example e.V.",
		"privacy_policy_url":   "https://example.org/datenschutz",
		"legal_notice_url":     "https://example.org/impressum",
		"registration_enabled": true,
	}
	for key, value := range want {
		if body[key] != value {
			t.Errorf("%s=%v, want %v", key, body[key], value)
		}
	}
}

func TestInstanceOmitsUnconfiguredFields(t *testing.T) {
	h, _ := newInstanceHandler(t, &config.Config{ServiceName: "KoalaCast"})
	body := getInstance(t, h, nil)
	for _, key := range []string{"operator_name", "privacy_policy_url", "legal_notice_url"} {
		if _, present := body[key]; present {
			t.Errorf("%s must be omitted when unconfigured, got %v", key, body[key])
		}
	}
}

func TestInstanceIgnoresRequestHeaders(t *testing.T) {
	h, _ := newInstanceHandler(t, &config.Config{ServiceName: "KoalaCast", PrivacyPolicyURL: "https://example.org/p"})
	body := getInstance(t, h, map[string]string{
		"Host": "evil.example", "X-Forwarded-Host": "evil.example", "X-Forwarded-Proto": "http", "Origin": "https://evil.example",
	})
	if body["privacy_policy_url"] != "https://example.org/p" {
		t.Fatalf("privacy_policy_url must come from config, got %v", body["privacy_policy_url"])
	}
}

func TestInstanceRegistrationFollowsEnvOverrideAndAdminSetting(t *testing.T) {
	off := false
	h, database := newInstanceHandler(t, &config.Config{ServiceName: "KoalaCast", RegistrationEnabledEnv: &off})
	if body := getInstance(t, h, nil); body["registration_enabled"] != false {
		t.Fatalf("env override false must win, got %v", body["registration_enabled"])
	}

	h2 := &InstanceHandler{Config: &config.Config{ServiceName: "KoalaCast"}, Auth: &AuthHandler{DB: database, Config: &config.Config{}}}
	if _, err := database.SQL.Exec(`INSERT INTO app_settings (key, value, updated_at) VALUES ('registration_enabled', 'false', 0)`); err != nil {
		t.Fatalf("seed setting: %v", err)
	}
	if body := getInstance(t, h2, nil); body["registration_enabled"] != false {
		t.Fatalf("admin setting false must apply, got %v", body["registration_enabled"])
	}
}

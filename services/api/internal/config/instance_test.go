package config

import "testing"

func instanceEnv(t *testing.T, kv map[string]string) {
	t.Helper()
	t.Setenv("APP_ENV", "production")
	t.Setenv("SESSION_SECRET", "a-very-secure-production-secret-with-at-least-32-characters")
	for _, key := range []string{"PUBLIC_BASE_URL", "KC_SERVICE_NAME", "KC_OPERATOR_NAME", "KC_PRIVACY_POLICY_URL", "KC_LEGAL_NOTICE_URL"} {
		t.Setenv(key, "")
	}
	for key, value := range kv {
		t.Setenv(key, value)
	}
}

func TestValidLegalURL(t *testing.T) {
	good := []string{"https://cast.example.org/privacy", "http://localhost:3000/privacy", "http://127.0.0.1/p", "http://[::1]:8080/p"}
	bad := []string{"", "cast.example.org/privacy", "/privacy", "http://cast.example.org/privacy", "ftp://x.example/p", "https://user:pw@x.example/p", "javascript:alert(1)", "https:///nohost"}
	for _, raw := range good {
		if !ValidLegalURL(raw) {
			t.Errorf("ValidLegalURL(%q)=false, want true", raw)
		}
	}
	for _, raw := range bad {
		if ValidLegalURL(raw) {
			t.Errorf("ValidLegalURL(%q)=true, want false", raw)
		}
	}
}

func TestInstanceIdentityDefaultsKeepExistingDeploymentsStarting(t *testing.T) {
	instanceEnv(t, map[string]string{"PUBLIC_BASE_URL": "https://cast.example.org/"})
	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.ServiceName != "KoalaCast" || cfg.OperatorName != "" || cfg.LegalNoticeURL != "" {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
	if cfg.PrivacyPolicyURL != "https://cast.example.org/privacy" {
		t.Fatalf("privacy default = %q", cfg.PrivacyPolicyURL)
	}
}

func TestInstanceIdentityDoesNotPublishInsecureDerivedPrivacyURL(t *testing.T) {
	instanceEnv(t, map[string]string{"PUBLIC_BASE_URL": "http://cast.example.org"})
	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.PrivacyPolicyURL != "" {
		t.Fatalf("derived http URL must not be published, got %q", cfg.PrivacyPolicyURL)
	}
}

func TestInstanceIdentityExplicitValues(t *testing.T) {
	instanceEnv(t, map[string]string{
		"KC_SERVICE_NAME":       " My Cast ",
		"KC_OPERATOR_NAME":      "Example e.V.",
		"KC_PRIVACY_POLICY_URL": "https://example.org/datenschutz",
		"KC_LEGAL_NOTICE_URL":   "https://example.org/impressum",
	})
	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.ServiceName != "My Cast" || cfg.OperatorName != "Example e.V." ||
		cfg.PrivacyPolicyURL != "https://example.org/datenschutz" || cfg.LegalNoticeURL != "https://example.org/impressum" {
		t.Fatalf("unexpected config: %+v", cfg)
	}
}

func TestInstanceIdentityRejectsMalformedValues(t *testing.T) {
	cases := map[string]map[string]string{
		"insecure privacy": {"KC_PRIVACY_POLICY_URL": "http://example.org/p"},
		"relative legal":   {"KC_LEGAL_NOTICE_URL": "/impressum"},
		"control chars":    {"KC_OPERATOR_NAME": "Evil\nName"},
		"too long":         {"KC_SERVICE_NAME": repeat('a', 201)},
	}
	for name, env := range cases {
		t.Run(name, func(t *testing.T) {
			instanceEnv(t, env)
			if _, err := LoadConfig(); err == nil {
				t.Fatal("expected an error")
			}
		})
	}
}

func repeat(r rune, n int) string {
	out := make([]rune, n)
	for i := range out {
		out[i] = r
	}
	return string(out)
}

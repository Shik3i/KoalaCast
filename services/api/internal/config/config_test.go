package config

import (
	"os"
	"testing"
)

func TestConfig_SessionSecretValidation(t *testing.T) {
	// Clear env
	os.Unsetenv("SESSION_SECRET")
	os.Unsetenv("APP_ENV")

	// Case 1: Unset in Production -> Must Fail
	os.Setenv("APP_ENV", "production")
	_, err := LoadConfig()
	if err == nil {
		t.Errorf("expected error when SESSION_SECRET is unset in production, got nil")
	}

	// Case 2: Short Secret in Production -> Must Fail
	os.Setenv("SESSION_SECRET", "short-secret")
	_, err = LoadConfig()
	if err == nil {
		t.Errorf("expected error when SESSION_SECRET is too short, got nil")
	}

	// Case 3: Known Insecure Secret -> Must Fail
	os.Setenv("SESSION_SECRET", "default-dev-secret-change-in-production")
	_, err = LoadConfig()
	if err == nil {
		t.Errorf("expected error when SESSION_SECRET is a known placeholder, got nil")
	}

	// Case 4: Valid 32+ char Secret -> Must Succeed
	os.Setenv("SESSION_SECRET", "a-very-secure-production-secret-with-at-least-32-characters")
	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error for valid secret: %v", err)
	}
	if cfg.SessionSecret != "a-very-secure-production-secret-with-at-least-32-characters" {
		t.Errorf("unexpected secret value: %s", cfg.SessionSecret)
	}

	// Case 5: Unset in Development -> Generates ephemeral secret
	os.Unsetenv("SESSION_SECRET")
	os.Setenv("APP_ENV", "development")
	devCfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error in development mode: %v", err)
	}
	if len(devCfg.SessionSecret) < 32 {
		t.Errorf("expected generated dev secret to be at least 32 chars, got length %d", len(devCfg.SessionSecret))
	}
}

func TestConfig_RegistrationEnabledOverride(t *testing.T) {
	// t.Setenv restores the previous value when the test ends. Plain os.Setenv
	// leaked KC_REGISTRATION_ENABLED="invalid-bool" out of case 4, which made
	// TestConfig_SessionSecretValidation fail on any repeated run (go test -count>1).
	t.Setenv("SESSION_SECRET", "a-very-secure-production-secret-with-at-least-32-characters")
	t.Setenv("APP_ENV", "production")
	t.Setenv("KC_REGISTRATION_ENABLED", "")

	// Case 1: Environment Unset -> RegistrationEnabledEnv must be nil
	os.Unsetenv("KC_REGISTRATION_ENABLED")
	cfg1, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg1.RegistrationEnabledEnv != nil {
		t.Errorf("expected RegistrationEnabledEnv to be nil when env is unset, got %v", *cfg1.RegistrationEnabledEnv)
	}

	// Case 2: Environment Set to true -> Must be non-nil pointer to true
	os.Setenv("KC_REGISTRATION_ENABLED", "true")
	cfg2, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg2.RegistrationEnabledEnv == nil || *cfg2.RegistrationEnabledEnv != true {
		t.Errorf("expected RegistrationEnabledEnv to be pointer to true")
	}

	// Case 3: Environment Set to false -> Must be non-nil pointer to false
	os.Setenv("KC_REGISTRATION_ENABLED", "false")
	cfg3, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg3.RegistrationEnabledEnv == nil || *cfg3.RegistrationEnabledEnv != false {
		t.Errorf("expected RegistrationEnabledEnv to be pointer to false")
	}

	// Case 4: Invalid Environment Value -> Must fail
	os.Setenv("KC_REGISTRATION_ENABLED", "invalid-bool")
	_, err = LoadConfig()
	if err == nil {
		t.Errorf("expected error for invalid KC_REGISTRATION_ENABLED value, got nil")
	}
}

func TestConfig_AudioEffectsProxyDefaultsOnAndSupportsOptOut(t *testing.T) {
	t.Setenv("SESSION_SECRET", "a-very-secure-production-secret-with-at-least-32-characters")
	t.Setenv("APP_ENV", "production")
	t.Setenv("KC_AUDIO_EFFECTS_PROXY_ENABLED", "")

	defaultCfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error with default proxy setting: %v", err)
	}
	if !defaultCfg.AudioEffectsProxyEnabled {
		t.Fatal("expected audio effects proxy to be enabled by default")
	}

	t.Setenv("KC_AUDIO_EFFECTS_PROXY_ENABLED", "false")
	disabledCfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error with disabled proxy setting: %v", err)
	}
	if disabledCfg.AudioEffectsProxyEnabled {
		t.Fatal("expected audio effects proxy to support an explicit opt-out")
	}
}

func TestConfig_WebPushRequiresCompleteValidVAPIDConfiguration(t *testing.T) {
	t.Setenv("SESSION_SECRET", "a-very-secure-production-secret-with-at-least-32-characters")
	t.Setenv("APP_ENV", "production")
	t.Setenv("WEB_PUSH_VAPID_PUBLIC_KEY", "public")
	t.Setenv("WEB_PUSH_VAPID_PRIVATE_KEY", "")
	t.Setenv("WEB_PUSH_VAPID_SUBJECT", "mailto:admin@example.com")
	if _, err := LoadConfig(); err == nil {
		t.Fatal("expected an incomplete VAPID key pair to fail")
	}

	t.Setenv("WEB_PUSH_VAPID_PRIVATE_KEY", "private")
	t.Setenv("WEB_PUSH_VAPID_SUBJECT", "http://cast.example")
	if _, err := LoadConfig(); err == nil {
		t.Fatal("expected an insecure VAPID subject to fail")
	}

	t.Setenv("WEB_PUSH_VAPID_SUBJECT", "https://cast.example")
	if _, err := LoadConfig(); err != nil {
		t.Fatalf("expected HTTPS VAPID subject to succeed: %v", err)
	}
}

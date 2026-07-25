package config

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	AppEnv                 string
	Port                   string
	PublicBaseURL          string
	APIBaseURL             string
	LogLevel               slog.Level
	DatabasePath           string
	SessionSecret          string
	RegistrationEnabledEnv *bool // nil if unset/empty (defer to DB admin setting), non-nil acts as hard override
	TrustedProxies         []string
	SecureCookies          bool
	PodcastIndexKey        string
	PodcastIndexSecret     string
	AdminUsername          string
	AdminPassword          string
	FeedWorkerConcurrency  int
	FeedRequestTimeoutMS   int
	FeedMaxResponseBytes   int64
	AllowedCORSOrigins     []string
}

var knownInsecureSecrets = []string{
	"default-dev-secret-change-in-production",
	"secret",
	"password",
	"change-me",
	"12345678901234567890123456789012",
}

func LoadConfig() (*Config, error) {
	appEnv := strings.ToLower(getEnv("APP_ENV", "production"))

	cfg := &Config{
		AppEnv:                appEnv,
		Port:                  getEnv("PORT", "3000"),
		PublicBaseURL:         getEnv("PUBLIC_BASE_URL", "http://localhost:3000"),
		APIBaseURL:            getEnv("API_BASE_URL", "http://localhost:3000/api/v1"),
		DatabasePath:          getEnv("DATABASE_PATH", "./data/koalacast.db"),
		SessionSecret:         os.Getenv("SESSION_SECRET"),
		PodcastIndexKey:       os.Getenv("PODCAST_INDEX_KEY"),
		PodcastIndexSecret:    os.Getenv("PODCAST_INDEX_SECRET"),
		AdminUsername:         os.Getenv("ADMIN_USERNAME"),
		AdminPassword:         os.Getenv("ADMIN_PASSWORD"),
		FeedWorkerConcurrency: getEnvInt("FEED_WORKER_CONCURRENCY", 5),
		FeedRequestTimeoutMS:  getEnvInt("FEED_REQUEST_TIMEOUT_MS", 15000),
		FeedMaxResponseBytes:  int64(getEnvInt("FEED_MAX_RESPONSE_BYTES", 10485760)),
		// Secure cookies default ON in production; a deployment terminating TLS
		// elsewhere (e.g. plain-HTTP local demo behind a proxy) can opt out with
		// SECURE_COOKIES=false.
		SecureCookies: getEnvBool("SECURE_COOKIES", appEnv == "production"),
	}

	// Parse LogLevel
	levelStr := strings.ToLower(getEnv("LOG_LEVEL", "info"))
	switch levelStr {
	case "debug":
		cfg.LogLevel = slog.LevelDebug
	case "warn":
		cfg.LogLevel = slog.LevelWarn
	case "error":
		cfg.LogLevel = slog.LevelError
	default:
		cfg.LogLevel = slog.LevelInfo
	}

	// Session Secret Validation
	if cfg.SessionSecret == "" {
		if appEnv == "development" || appEnv == "dev" {
			// Generate ephemeral development secret
			randomBytes := make([]byte, 32)
			_, _ = rand.Read(randomBytes)
			cfg.SessionSecret = hex.EncodeToString(randomBytes)
			slog.Warn("SESSION_SECRET is unset in development mode; generated ephemeral runtime secret")
		} else {
			return nil, fmt.Errorf("SESSION_SECRET environment variable is required in production mode and must be at least 32 characters long")
		}
	} else {
		if len(cfg.SessionSecret) < 32 {
			return nil, fmt.Errorf("SESSION_SECRET is too short (%d chars); minimum required length is 32 characters", len(cfg.SessionSecret))
		}
		for _, known := range knownInsecureSecrets {
			if strings.EqualFold(cfg.SessionSecret, known) {
				return nil, fmt.Errorf("SESSION_SECRET matches a known insecure placeholder; please set a strong unique secret")
			}
		}
	}

	// KC_REGISTRATION_ENABLED environment override check
	if valStr, exists := os.LookupEnv("KC_REGISTRATION_ENABLED"); exists && strings.TrimSpace(valStr) != "" {
		b, err := strconv.ParseBool(strings.TrimSpace(valStr))
		if err != nil {
			return nil, fmt.Errorf("invalid KC_REGISTRATION_ENABLED value (%s): %w", valStr, err)
		}
		cfg.RegistrationEnabledEnv = &b
	}

	// Parse Trusted Proxies
	if proxies := os.Getenv("TRUSTED_PROXIES"); proxies != "" {
		cfg.TrustedProxies = splitClean(proxies, ",")
	} else {
		cfg.TrustedProxies = []string{"127.0.0.1", "::1"}
	}

	// Parse CORS Origins
	if cors := os.Getenv("ALLOWED_CORS_ORIGINS"); cors != "" {
		cfg.AllowedCORSOrigins = splitClean(cors, ",")
	}

	return cfg, nil
}

func getEnv(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}

func getEnvInt(key string, defaultVal int) int {
	if valStr := os.Getenv(key); valStr != "" {
		if val, err := strconv.Atoi(valStr); err == nil {
			return val
		}
	}
	return defaultVal
}

func getEnvBool(key string, defaultVal bool) bool {
	if valStr := os.Getenv(key); valStr != "" {
		if val, err := strconv.ParseBool(valStr); err == nil {
			return val
		}
	}
	return defaultVal
}

func splitClean(s, sep string) []string {
	parts := strings.Split(s, sep)
	res := make([]string, 0, len(parts))
	for _, p := range parts {
		trimmed := strings.TrimSpace(p)
		if trimmed != "" {
			res = append(res, trimmed)
		}
	}
	return res
}

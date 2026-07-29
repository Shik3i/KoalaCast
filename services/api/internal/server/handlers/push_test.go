package handlers

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
)

func TestPushHandlerSubscribeStoresAuthenticatedEndpoint(t *testing.T) {
	database, err := db.OpenDB(
		filepath.Join(t.TempDir(), "push.db"),
		slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelError})),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	now := time.Now().UnixMilli()
	if _, err := database.SQL.Exec(`
		INSERT INTO users (
			id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at
		) VALUES ('push-user', 'Push User', 'push user', 'hash', 'recovery', ?, ?)
	`, now, now); err != nil {
		t.Fatal(err)
	}
	handler := &PushHandler{
		DB: database,
		Config: &config.Config{
			PublicBaseURL:          "https://cast.example",
			WebPushVAPIDPublicKey:  "public",
			WebPushVAPIDPrivateKey: "private",
		},
	}
	body := []byte(`{
		"endpoint":"https://push.example/subscription",
		"expirationTime":0,
		"locale":"de-DE",
		"keys":{"p256dh":"abcdefghijklmnopqrstuvwxyz1234567890","auth":"12345678"}
	}`)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/push/subscriptions", bytes.NewReader(body))
	request.Header.Set("Origin", "https://cast.example")
	request = request.WithContext(context.WithValue(
		request.Context(),
		customMiddleware.UserContextKey,
		&customMiddleware.AuthUser{ID: "push-user"},
	))
	response := httptest.NewRecorder()
	handler.Subscribe(response, request)
	if response.Code != http.StatusNoContent {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	var userID, locale string
	if err := database.SQL.QueryRow(
		"SELECT user_id, locale FROM web_push_subscriptions WHERE endpoint = 'https://push.example/subscription'",
	).Scan(&userID, &locale); err != nil {
		t.Fatal(err)
	}
	if userID != "push-user" || locale != "de" {
		t.Fatalf("user=%q locale=%q", userID, locale)
	}
}

func TestValidPushSubscriptionRejectsUnsafeEndpoints(t *testing.T) {
	valid := pushSubscriptionRequest{Endpoint: "https://push.example/subscription"}
	valid.Keys.P256dh = "abcdefghijklmnopqrstuvwxyz1234567890"
	valid.Keys.Auth = "12345678"
	if !validPushSubscription(valid) {
		t.Fatal("expected public HTTPS endpoint to be valid")
	}

	for _, endpoint := range []string{
		"http://push.example/subscription",
		"https://127.0.0.1/subscription",
		"https://169.254.169.254/subscription",
		"https://user:password@push.example/subscription",
	} {
		request := valid
		request.Endpoint = endpoint
		if validPushSubscription(request) {
			t.Fatalf("expected endpoint to be rejected: %s", endpoint)
		}
	}
}

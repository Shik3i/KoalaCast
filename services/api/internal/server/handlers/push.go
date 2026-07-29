package handlers

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	"github.com/Shik3i/KoalaCast/services/api/internal/rss"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
	"github.com/google/uuid"
)

type PushHandler struct {
	DB     *db.DB
	Config *config.Config
}

type pushSubscriptionRequest struct {
	Endpoint       string `json:"endpoint"`
	ExpirationTime int64  `json:"expirationTime"`
	Locale         string `json:"locale"`
	Keys           struct {
		P256dh string `json:"p256dh"`
		Auth   string `json:"auth"`
	} `json:"keys"`
}

func (h *PushHandler) GetConfig(w http.ResponseWriter, _ *http.Request) {
	configured := h.Config.WebPushVAPIDPublicKey != "" && h.Config.WebPushVAPIDPrivateKey != ""
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"configured":       configured,
		"vapid_public_key": h.Config.WebPushVAPIDPublicKey,
	})
}

func (h *PushHandler) Subscribe(w http.ResponseWriter, r *http.Request) {
	if !h.sameOrigin(r) {
		http.Error(w, `{"error":"cross-origin push subscription rejected"}`, http.StatusForbidden)
		return
	}
	if h.Config.WebPushVAPIDPublicKey == "" || h.Config.WebPushVAPIDPrivateKey == "" {
		http.Error(w, `{"error":"web push is not configured"}`, http.StatusServiceUnavailable)
		return
	}
	var request pushSubscriptionRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024)).Decode(&request); err != nil {
		http.Error(w, `{"error":"invalid push subscription"}`, http.StatusBadRequest)
		return
	}
	if !validPushSubscription(request) {
		http.Error(w, `{"error":"invalid push subscription"}`, http.StatusBadRequest)
		return
	}
	authUser := customMiddleware.GetAuthUser(r.Context())
	nowMs := time.Now().UnixMilli()
	locale := strings.ToLower(strings.TrimSpace(request.Locale))
	if !strings.HasPrefix(locale, "de") {
		locale = "en"
	} else {
		locale = "de"
	}
	_, err := h.DB.SQL.ExecContext(r.Context(), `
		INSERT INTO web_push_subscriptions (
			id, user_id, endpoint, p256dh, auth, locale, expiration_time, created_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(endpoint) DO UPDATE SET
			user_id = excluded.user_id,
			p256dh = excluded.p256dh,
			auth = excluded.auth,
			locale = excluded.locale,
			expiration_time = excluded.expiration_time,
			updated_at = excluded.updated_at
	`, uuid.NewString(), authUser.ID, request.Endpoint, request.Keys.P256dh, request.Keys.Auth,
		locale, request.ExpirationTime, nowMs, nowMs)
	if err != nil {
		http.Error(w, `{"error":"failed to save push subscription"}`, http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *PushHandler) Unsubscribe(w http.ResponseWriter, r *http.Request) {
	if !h.sameOrigin(r) {
		http.Error(w, `{"error":"cross-origin push subscription rejected"}`, http.StatusForbidden)
		return
	}
	var request struct {
		Endpoint string `json:"endpoint"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8*1024)).Decode(&request); err != nil || request.Endpoint == "" {
		http.Error(w, `{"error":"invalid push subscription"}`, http.StatusBadRequest)
		return
	}
	authUser := customMiddleware.GetAuthUser(r.Context())
	_, _ = h.DB.SQL.ExecContext(r.Context(),
		"DELETE FROM web_push_subscriptions WHERE user_id = ? AND endpoint = ?",
		authUser.ID, request.Endpoint)
	w.WriteHeader(http.StatusNoContent)
}

func (h *PushHandler) sameOrigin(r *http.Request) bool {
	origin := strings.TrimSpace(r.Header.Get("Origin"))
	if origin == "" {
		return true
	}
	parsed, err := url.Parse(origin)
	if err != nil || parsed.Host == "" {
		return false
	}
	if strings.EqualFold(parsed.Host, r.Host) {
		return true
	}
	if configured, err := url.Parse(h.Config.PublicBaseURL); err == nil && configured.Host != "" {
		return strings.EqualFold(parsed.Host, configured.Host)
	}
	return false
}

func validPushSubscription(request pushSubscriptionRequest) bool {
	endpoint, err := url.Parse(request.Endpoint)
	if err != nil || endpoint.Scheme != "https" || endpoint.Host == "" || rss.ValidateURL(request.Endpoint) != nil {
		return false
	}
	return len(request.Endpoint) <= 4096 &&
		len(request.Keys.P256dh) >= 32 && len(request.Keys.P256dh) <= 512 &&
		len(request.Keys.Auth) >= 8 && len(request.Keys.Auth) <= 256
}

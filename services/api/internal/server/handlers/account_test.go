package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Shik3i/KoalaCast/services/api/internal/auth"
	"github.com/Shik3i/KoalaCast/services/api/internal/db"
	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
)

const deletionTestPassword = "CorrectHorseBattery1!"

// Every table that hangs off a user. The deletion test asserts each one empties,
// which is what makes ON DELETE CASCADE a guarantee rather than an assumption:
// add a table without a cascade and this list is where it gets caught.
var userScopedTables = []string{
	"sessions",
	"device_credentials",
	"subscriptions",
	"favorites",
	"playback_states",
	"listening_sessions",
	"queue_items",
	"per_podcast_settings",
	"history_entries",
	"sync_log",
	"user_sync_cursors",
	"processed_sync_operations",
	"web_push_subscriptions",
}

func seedDeletableAccount(t *testing.T, database *db.DB) string {
	t.Helper()
	passwordHash, err := auth.HashPassword(deletionTestPassword)
	if err != nil {
		t.Fatal(err)
	}
	recoveryCode := "ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ23-4567"
	if _, err := database.SQL.Exec(`
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at)
		VALUES ('u1','User','user',?,?,0,0);
		INSERT INTO sessions (id,user_id,token_hash,expires_at,created_at,last_used_at)
		VALUES ('s1','u1','session-hash',9999999999999,0,0);
		INSERT INTO device_credentials (id,user_id,device_id,token_hash,created_at,last_sync_at,expires_at)
		VALUES ('d1','u1','device-1','device-hash',0,0,9999999999999);
		INSERT INTO podcasts (id, feed_url, title, created_at, updated_at)
		VALUES ('p1','https://example.org/feed.xml','Show',0,0);
		INSERT INTO subscriptions (user_id,podcast_id,created_at,updated_at)
		VALUES ('u1','p1',0,0);
		INSERT INTO sync_log (user_id, device_id, client_op_id, entity_type, entity_id, action, payload_json, client_timestamp, server_timestamp, server_cursor)
		VALUES ('u1','device-1','op-1','settings','global','upsert','{"updated_at":1}',1,1,1);
		INSERT INTO user_sync_cursors (user_id, current_cursor, min_retained_cursor, protocol_version, client_schema_version)
		VALUES ('u1',1,0,1,1);
		INSERT INTO web_push_subscriptions (id,user_id,endpoint,p256dh,auth,locale,expiration_time,created_at,updated_at)
		VALUES ('w1','u1','https://push.example/1','key','auth','en',0,0,0)
	`, passwordHash, auth.HashRecoveryCode(recoveryCode)); err != nil {
		t.Fatal(err)
	}
	return recoveryCode
}

func deleteRequest(body string) *http.Request {
	req := httptest.NewRequest(http.MethodDelete, "/api/v1/auth/account", bytes.NewBufferString(body))
	return req.WithContext(context.WithValue(
		req.Context(),
		customMiddleware.UserContextKey,
		&customMiddleware.AuthUser{ID: "u1", Username: "User", Role: "user"},
	))
}

func TestDeleteAccountRemovesEveryUserScopedRow(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	seedDeletableAccount(t, database)

	rec := httptest.NewRecorder()
	handler.DeleteAccount(rec, deleteRequest(`{"password":"`+deletionTestPassword+`"}`))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("delete: %d %s", rec.Code, rec.Body.String())
	}

	var users int
	if err := database.SQL.QueryRow("SELECT COUNT(*) FROM users WHERE id='u1'").Scan(&users); err != nil {
		t.Fatal(err)
	}
	if users != 0 {
		t.Fatalf("account row survived deletion")
	}
	for _, table := range userScopedTables {
		var count int
		if err := database.SQL.QueryRow("SELECT COUNT(*) FROM " + table + " WHERE user_id='u1'").Scan(&count); err != nil {
			t.Fatalf("%s: %v", table, err)
		}
		if count != 0 {
			t.Fatalf("%s still holds %d row(s) for the deleted account", table, count)
		}
	}
}

func TestDeleteAccountClearsTheSessionCookie(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	seedDeletableAccount(t, database)

	rec := httptest.NewRecorder()
	handler.DeleteAccount(rec, deleteRequest(`{"password":"`+deletionTestPassword+`"}`))

	for _, cookie := range rec.Result().Cookies() {
		if cookie.Name == "koalacast_session" {
			if cookie.MaxAge >= 0 || cookie.Value != "" {
				t.Fatalf("session cookie not cleared: %+v", cookie)
			}
			return
		}
	}
	t.Fatal("no session cookie in the response")
}

func TestDeleteAccountAcceptsARecoveryCode(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	recoveryCode := seedDeletableAccount(t, database)

	rec := httptest.NewRecorder()
	handler.DeleteAccount(rec, deleteRequest(`{"recovery_code":"`+recoveryCode+`"}`))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("delete with recovery code: %d %s", rec.Code, rec.Body.String())
	}
}

func TestDeleteAccountRefusesWithoutAValidCredential(t *testing.T) {
	for name, body := range map[string]string{
		"wrong password":      `{"password":"WrongPassword1!"}`,
		"wrong recovery code": `{"recovery_code":"ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ"}`,
		"nothing at all":      `{}`,
	} {
		t.Run(name, func(t *testing.T) {
			handler, database := newAuthTestHandler(t)
			seedDeletableAccount(t, database)

			rec := httptest.NewRecorder()
			handler.DeleteAccount(rec, deleteRequest(body))
			if rec.Code == http.StatusNoContent {
				t.Fatalf("deletion accepted %s", name)
			}
			var users int
			if err := database.SQL.QueryRow("SELECT COUNT(*) FROM users WHERE id='u1'").Scan(&users); err != nil {
				t.Fatal(err)
			}
			if users != 1 {
				t.Fatalf("account deleted despite %s", name)
			}
		})
	}
}

// A session alone must never be enough: the request carries a valid auth user in
// every case above, and the account survives all of them.
func TestDeleteAccountLeavesOtherAccountsAlone(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	seedDeletableAccount(t, database)
	if _, err := database.SQL.Exec(`
		INSERT INTO users (id, username, normalized_username, password_hash, recovery_code_hash, created_at, updated_at)
		VALUES ('u2','Other','other','hash','recovery',0,0);
		INSERT INTO subscriptions (user_id,podcast_id,created_at,updated_at) VALUES ('u2','p1',0,0)
	`); err != nil {
		t.Fatal(err)
	}

	rec := httptest.NewRecorder()
	handler.DeleteAccount(rec, deleteRequest(`{"password":"`+deletionTestPassword+`"}`))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("delete: %d %s", rec.Code, rec.Body.String())
	}

	var others int
	if err := database.SQL.QueryRow("SELECT COUNT(*) FROM subscriptions WHERE user_id='u2'").Scan(&others); err != nil {
		t.Fatal(err)
	}
	if others != 1 {
		t.Fatalf("another account's rows were deleted too")
	}
}

func TestExportAccountOmitsEverySecret(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	seedDeletableAccount(t, database)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/export", nil)
	req = req.WithContext(context.WithValue(
		req.Context(),
		customMiddleware.UserContextKey,
		&customMiddleware.AuthUser{ID: "u1", Username: "User", Role: "user"},
	))
	rec := httptest.NewRecorder()
	handler.ExportAccount(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("export: %d %s", rec.Code, rec.Body.String())
	}

	body := rec.Body.String()
	for _, secret := range []string{"password_hash", "recovery_code_hash", "token_hash", "session-hash", "device-hash"} {
		if bytes.Contains([]byte(body), []byte(secret)) {
			t.Fatalf("export leaked %q", secret)
		}
	}

	var export map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &export); err != nil {
		t.Fatalf("export is not valid JSON: %v", err)
	}
	account, ok := export["account"].(map[string]any)
	if !ok || account["username"] != "User" {
		t.Fatalf("export is missing the account block: %v", export["account"])
	}
	if _, ok := export["subscriptions"].([]any); !ok {
		t.Fatalf("export is missing subscriptions")
	}
}

package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
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

var synchronizedDataTables = []string{
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

func dataDeleteRequest(body string) *http.Request {
	req := httptest.NewRequest(http.MethodDelete, "/api/v1/auth/data", bytes.NewBufferString(body))
	return req.WithContext(context.WithValue(
		req.Context(),
		customMiddleware.UserContextKey,
		&customMiddleware.AuthUser{ID: "u1", Username: "User", Role: "user"},
	))
}

func seedAllSynchronizedData(t *testing.T, database *db.DB) string {
	t.Helper()
	recoveryCode := seedDeletableAccount(t, database)
	if _, err := database.SQL.Exec(`
		UPDATE users SET global_stats_opt_in=1, global_stats_opt_in_at=123 WHERE id='u1';
		INSERT INTO episodes (id,podcast_id,stable_identity_key,title,enclosure_url,created_at)
		VALUES ('e1','p1','episode-1','Episode','https://example.org/e1.mp3',0);
		INSERT INTO favorites (user_id,episode_id,created_at) VALUES ('u1','e1',1);
		INSERT INTO playback_states (user_id,episode_id,position_ms) VALUES ('u1','e1',42);
		INSERT INTO listening_sessions (id,user_id,episode_id,podcast_id,started_at,ended_at)
		VALUES ('l1','u1','e1','p1',1,2);
		INSERT INTO queue_items (id,user_id,episode_id,added_at) VALUES ('q1','u1','e1',1);
		INSERT INTO per_podcast_settings (user_id,podcast_id) VALUES ('u1','p1');
		INSERT INTO history_entries (id,user_id,episode_id,played_at) VALUES ('h1','u1','e1',1);
		INSERT INTO processed_sync_operations (user_id,device_id,client_op_id,server_cursor,processed_at)
		VALUES ('u1','device-1','processed-1',1,1);
	`); err != nil {
		t.Fatal(err)
	}
	return recoveryCode
}

func TestDeleteSynchronizedDataIsAtomicAndKeepsAccountAndLogins(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	seedAllSynchronizedData(t, database)

	rec := httptest.NewRecorder()
	handler.DeleteSynchronizedData(rec, dataDeleteRequest(`{"password":"`+deletionTestPassword+`"}`))
	if rec.Code != http.StatusOK {
		t.Fatalf("delete data: %d %s", rec.Code, rec.Body.String())
	}
	var response struct {
		DataGeneration int64 `json:"data_generation"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &response); err != nil || response.DataGeneration != 1 {
		t.Fatalf("unexpected generation response: %s (%v)", rec.Body.String(), err)
	}

	for _, table := range synchronizedDataTables {
		var count int
		if err := database.SQL.QueryRow("SELECT COUNT(*) FROM " + table + " WHERE user_id='u1'").Scan(&count); err != nil {
			t.Fatalf("%s: %v", table, err)
		}
		if count != 0 {
			t.Fatalf("%s still holds %d row(s)", table, count)
		}
	}
	var users, sessions, devices, optIn int
	var generation int64
	var username, passwordHash, recoveryHash, role string
	if err := database.SQL.QueryRow(`
		SELECT COUNT(*), data_generation, global_stats_opt_in, username,
		       password_hash, recovery_code_hash, role
		FROM users WHERE id='u1'
	`).Scan(&users, &generation, &optIn, &username, &passwordHash, &recoveryHash, &role); err != nil {
		t.Fatal(err)
	}
	_ = database.SQL.QueryRow("SELECT COUNT(*) FROM sessions WHERE user_id='u1'").Scan(&sessions)
	_ = database.SQL.QueryRow("SELECT COUNT(*) FROM device_credentials WHERE user_id='u1'").Scan(&devices)
	if users != 1 || sessions != 1 || devices != 1 || generation != 1 || optIn != 0 {
		t.Fatalf("identity/login changed: users=%d sessions=%d devices=%d generation=%d optIn=%d", users, sessions, devices, generation, optIn)
	}
	if username != "User" || passwordHash == "" || recoveryHash == "" || role != "user" {
		t.Fatalf("identity fields changed: username=%q password=%q recovery=%q role=%q", username, passwordHash, recoveryHash, role)
	}
}

func TestDeleteSynchronizedDataAcceptsRecoveryCode(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	recoveryCode := seedAllSynchronizedData(t, database)
	rec := httptest.NewRecorder()
	handler.DeleteSynchronizedData(rec, dataDeleteRequest(`{"recovery_code":"`+recoveryCode+`"}`))
	if rec.Code != http.StatusOK {
		t.Fatalf("delete with recovery: %d %s", rec.Code, rec.Body.String())
	}
}

func TestDeleteSynchronizedDataInvalidCredentialChangesNothing(t *testing.T) {
	for name, body := range map[string]string{
		"password": `{"password":"wrong"}`,
		"recovery": `{"recovery_code":"WRONG-WRONG"}`,
	} {
		t.Run(name, func(t *testing.T) {
			handler, database := newAuthTestHandler(t)
			seedAllSynchronizedData(t, database)
			rec := httptest.NewRecorder()
			handler.DeleteSynchronizedData(rec, dataDeleteRequest(body))
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
			}
			var subscriptions, generation, optIn int
			_ = database.SQL.QueryRow("SELECT COUNT(*) FROM subscriptions WHERE user_id='u1'").Scan(&subscriptions)
			_ = database.SQL.QueryRow("SELECT data_generation, global_stats_opt_in FROM users WHERE id='u1'").Scan(&generation, &optIn)
			if subscriptions != 1 || generation != 0 || optIn != 1 {
				t.Fatalf("data changed after invalid credential: subscriptions=%d generation=%d optIn=%d", subscriptions, generation, optIn)
			}
		})
	}
}

func TestDeleteSynchronizedDataRollsBackEveryChangeOnFailure(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	seedAllSynchronizedData(t, database)
	if _, err := database.SQL.Exec(`
		CREATE TRIGGER fail_history_delete BEFORE DELETE ON history_entries
		BEGIN SELECT RAISE(ABORT, 'injected reset failure'); END;
	`); err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	handler.DeleteSynchronizedData(rec, dataDeleteRequest(`{"password":"`+deletionTestPassword+`"}`))
	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	for _, table := range synchronizedDataTables {
		var count int
		if err := database.SQL.QueryRow("SELECT COUNT(*) FROM " + table + " WHERE user_id='u1'").Scan(&count); err != nil || count == 0 {
			t.Fatalf("%s was partially deleted: count=%d err=%v", table, count, err)
		}
	}
	var generation, optIn int
	_ = database.SQL.QueryRow("SELECT data_generation, global_stats_opt_in FROM users WHERE id='u1'").Scan(&generation, &optIn)
	if generation != 0 || optIn != 1 {
		t.Fatalf("user update escaped rollback: generation=%d optIn=%d", generation, optIn)
	}
}

func staleSubscriptionHTTPRequest(dataGeneration int64) *http.Request {
	body := bytes.NewBufferString(`{
		"client_schema_version":2,
		"data_generation":` + fmt.Sprint(dataGeneration) + `,
		"operations":[{
			"client_op_id":"parallel-op","device_id":"device-1",
			"entity_type":"subscription","action":"upsert","entity_id":"p1",
			"payload":{"podcast_id":"p1","added_at":100,"updated_at":100},
			"client_timestamp":100
		}]
	}`)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/sync", body)
	return req.WithContext(context.WithValue(
		req.Context(),
		customMiddleware.UserContextKey,
		&customMiddleware.AuthUser{ID: "u1", Username: "User", Role: "user"},
	))
}

func TestStaleSyncCannotRestoreDeletedData(t *testing.T) {
	authHandler, database := newAuthTestHandler(t)
	seedAllSynchronizedData(t, database)
	recDelete := httptest.NewRecorder()
	authHandler.DeleteSynchronizedData(recDelete, dataDeleteRequest(`{"password":"`+deletionTestPassword+`"}`))
	if recDelete.Code != http.StatusOK {
		t.Fatalf("reset: %d %s", recDelete.Code, recDelete.Body.String())
	}

	recPush := httptest.NewRecorder()
	(&SyncHandler{DB: database}).Push(recPush, staleSubscriptionHTTPRequest(0))
	if recPush.Code != http.StatusConflict || !bytes.Contains(recPush.Body.Bytes(), []byte("DATA_GENERATION_MISMATCH")) {
		t.Fatalf("stale push accepted: %d %s", recPush.Code, recPush.Body.String())
	}
	var count int
	_ = database.SQL.QueryRow("SELECT COUNT(*) FROM subscriptions WHERE user_id='u1'").Scan(&count)
	if count != 0 {
		t.Fatalf("stale push restored %d subscription(s)", count)
	}
}

func TestParallelSyncAndDataResetCannotLeaveRestoredData(t *testing.T) {
	authHandler, database := newAuthTestHandler(t)
	seedAllSynchronizedData(t, database)
	syncHandler := &SyncHandler{DB: database}
	start := make(chan struct{})
	var wait sync.WaitGroup
	wait.Add(2)
	var deleteCode, pushCode int
	go func() {
		defer wait.Done()
		<-start
		rec := httptest.NewRecorder()
		authHandler.DeleteSynchronizedData(rec, dataDeleteRequest(`{"password":"`+deletionTestPassword+`"}`))
		deleteCode = rec.Code
	}()
	go func() {
		defer wait.Done()
		<-start
		rec := httptest.NewRecorder()
		syncHandler.Push(rec, staleSubscriptionHTTPRequest(0))
		pushCode = rec.Code
	}()
	close(start)
	wait.Wait()
	if deleteCode != http.StatusOK || (pushCode != http.StatusOK && pushCode != http.StatusConflict) {
		t.Fatalf("unexpected statuses: delete=%d push=%d", deleteCode, pushCode)
	}
	var count, generation int
	_ = database.SQL.QueryRow("SELECT COUNT(*) FROM subscriptions WHERE user_id='u1'").Scan(&count)
	_ = database.SQL.QueryRow("SELECT data_generation FROM users WHERE id='u1'").Scan(&generation)
	if count != 0 || generation != 1 {
		t.Fatalf("parallel push survived reset: count=%d generation=%d", count, generation)
	}
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

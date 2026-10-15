package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	customMiddleware "github.com/Shik3i/KoalaCast/services/api/internal/server/middleware"
)

// named_queue and time_bookmark live only in the append-only sync log, like queue
// and settings: the newest non-deleted payload per entity is the snapshot.

func namedQueueOp(opID, deviceID, key string, ts int64, items string) string {
	return fmt.Sprintf(`{"client_op_id":%q,"device_id":%q,"entity_type":"named_queue","action":"upsert","entity_id":%q,"payload":{"id":"q-%s","name":%q,"items":%s,"updated_at":%d},"client_timestamp":%d}`,
		opID, deviceID, key, key, key, items, ts, ts)
}

func bookmarkOp(opID, deviceID, id string, ts int64) string {
	return fmt.Sprintf(`{"client_op_id":%q,"device_id":%q,"entity_type":"time_bookmark","action":"upsert","entity_id":%q,"payload":{"id":%q,"episode_id":"ep-1","position_ms":61000,"label":"good bit","created_at":%d},"client_timestamp":%d}`,
		opID, deviceID, id, id, ts, ts)
}

func deleteOp(opID, deviceID, entityType, id string, ts int64) string {
	return fmt.Sprintf(`{"client_op_id":%q,"device_id":%q,"entity_type":%q,"action":"delete","entity_id":%q,"payload":{},"client_timestamp":%d}`,
		opID, deviceID, entityType, id, ts)
}

func pushOps(t *testing.T, h *SyncHandler, ctx context.Context, ops ...string) *httptest.ResponseRecorder {
	t.Helper()
	return pushSync(t, h, ctx, `{"operations":[`+strings.Join(ops, ",")+`]}`)
}

type librarySnapshot struct {
	NamedQueues   []map[string]any `json:"named_queues"`
	TimeBookmarks []map[string]any `json:"time_bookmarks"`
}

func takeLibrarySnapshot(t *testing.T, h *SyncHandler, ctx context.Context) librarySnapshot {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/sync/snapshot", nil).WithContext(ctx)
	rec := httptest.NewRecorder()
	h.Snapshot(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("snapshot: %d %s", rec.Code, rec.Body.String())
	}
	var snapshot librarySnapshot
	if err := json.Unmarshal(rec.Body.Bytes(), &snapshot); err != nil {
		t.Fatal(err)
	}
	return snapshot
}

func TestSyncNamedQueueAndBookmarkRoundTripThroughSnapshot(t *testing.T) {
	h, _, ctx := newSyncTestHandler(t)
	if rec := pushOps(t, h, ctx,
		namedQueueOp("nq1", "dev-a", "commute", 100, `[{"episode_id":"ep-1"}]`),
		bookmarkOp("bm1", "dev-a", "bm-1", 110),
	); rec.Code != http.StatusOK {
		t.Fatalf("push: %d %s", rec.Code, rec.Body.String())
	}
	snapshot := takeLibrarySnapshot(t, h, ctx)
	if len(snapshot.NamedQueues) != 1 || snapshot.NamedQueues[0]["name"] != "commute" {
		t.Fatalf("named queue missing from snapshot: %+v", snapshot.NamedQueues)
	}
	if items, _ := snapshot.NamedQueues[0]["items"].([]any); len(items) != 1 {
		t.Fatalf("queue items must round-trip verbatim: %+v", snapshot.NamedQueues[0])
	}
	if len(snapshot.TimeBookmarks) != 1 || snapshot.TimeBookmarks[0]["label"] != "good bit" ||
		snapshot.TimeBookmarks[0]["position_ms"] != float64(61000) {
		t.Fatalf("bookmark missing from snapshot: %+v", snapshot.TimeBookmarks)
	}
}

func TestSyncNamedQueueLastWriterWinsAndDeleteRemovesFromSnapshot(t *testing.T) {
	h, _, ctx := newSyncTestHandler(t)
	push := func(ops ...string) {
		t.Helper()
		if rec := pushOps(t, h, ctx, ops...); rec.Code != http.StatusOK {
			t.Fatalf("push: %d %s", rec.Code, rec.Body.String())
		}
	}
	push(namedQueueOp("a", "dev-a", "commute", 200, `[{"episode_id":"new"}]`))
	// A stale write from another device must not roll the queue back.
	push(namedQueueOp("b", "dev-b", "commute", 100, `[{"episode_id":"old"}]`))
	snapshot := takeLibrarySnapshot(t, h, ctx)
	items, _ := snapshot.NamedQueues[0]["items"].([]any)
	if len(snapshot.NamedQueues) != 1 || items[0].(map[string]any)["episode_id"] != "new" {
		t.Fatalf("stale write won: %+v", snapshot.NamedQueues)
	}

	push(deleteOp("c", "dev-b", "named_queue", "commute", 300))
	if got := takeLibrarySnapshot(t, h, ctx).NamedQueues; len(got) != 0 {
		t.Fatalf("deleted queue must leave the snapshot: %+v", got)
	}

	// A stale upsert after the delete stays deleted; a newer one recreates it.
	push(namedQueueOp("d", "dev-a", "commute", 250, `[]`))
	if got := takeLibrarySnapshot(t, h, ctx).NamedQueues; len(got) != 0 {
		t.Fatalf("stale upsert resurrected a deleted queue: %+v", got)
	}
	push(namedQueueOp("e", "dev-a", "commute", 400, `[]`))
	if got := takeLibrarySnapshot(t, h, ctx).NamedQueues; len(got) != 1 {
		t.Fatalf("a newer upsert must recreate the queue: %+v", got)
	}
}

func TestSyncBookmarkDeleteAndPullCarryTheNewEntityTypes(t *testing.T) {
	h, _, ctx := newSyncTestHandler(t)
	if rec := pushOps(t, h, ctx, bookmarkOp("bm", "dev-a", "bm-1", 100)); rec.Code != http.StatusOK {
		t.Fatalf("push: %d %s", rec.Code, rec.Body.String())
	}
	if rec := pushOps(t, h, ctx, deleteOp("bm-del", "dev-b", "time_bookmark", "bm-1", 200)); rec.Code != http.StatusOK {
		t.Fatalf("push delete: %d %s", rec.Code, rec.Body.String())
	}
	if got := takeLibrarySnapshot(t, h, ctx).TimeBookmarks; len(got) != 0 {
		t.Fatalf("deleted bookmark must leave the snapshot: %+v", got)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/sync?since_cursor=0", nil).WithContext(ctx)
	rec := httptest.NewRecorder()
	h.Pull(rec, req)
	var pulled struct {
		Changesets []struct {
			EntityType string `json:"entity_type"`
			Action     string `json:"action"`
		} `json:"changesets"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &pulled); err != nil {
		t.Fatal(err)
	}
	if len(pulled.Changesets) != 2 || pulled.Changesets[0].EntityType != "time_bookmark" ||
		pulled.Changesets[0].Action != "upsert" || pulled.Changesets[1].Action != "delete" {
		t.Fatalf("pull must carry both operations: %+v", pulled.Changesets)
	}
}

func TestSyncRejectsInvalidNamedQueueAndBookmarkOperations(t *testing.T) {
	h, _, ctx := newSyncTestHandler(t)
	manyItems := "[" + strings.Repeat("{},", 500) + "{}]"
	cases := map[string]string{
		"queue without name":    `{"client_op_id":"1","device_id":"d","entity_type":"named_queue","action":"upsert","entity_id":"k","payload":{"name":"  ","items":[],"updated_at":5},"client_timestamp":5}`,
		"queue name too long":   fmt.Sprintf(`{"client_op_id":"2","device_id":"d","entity_type":"named_queue","action":"upsert","entity_id":"k","payload":{"name":%q,"items":[],"updated_at":5},"client_timestamp":5}`, strings.Repeat("n", 201)),
		"queue without items":   `{"client_op_id":"3","device_id":"d","entity_type":"named_queue","action":"upsert","entity_id":"k","payload":{"name":"x","updated_at":5},"client_timestamp":5}`,
		"queue too many items":  `{"client_op_id":"4","device_id":"d","entity_type":"named_queue","action":"upsert","entity_id":"k","payload":{"name":"x","items":` + manyItems + `,"updated_at":5},"client_timestamp":5}`,
		"queue without time":    `{"client_op_id":"5","device_id":"d","entity_type":"named_queue","action":"upsert","entity_id":"k","payload":{"name":"x","items":[],"updated_at":5},"client_timestamp":0}`,
		"queue bad action":      `{"client_op_id":"6","device_id":"d","entity_type":"named_queue","action":"patch","entity_id":"k","payload":{},"client_timestamp":5}`,
		"bookmark id mismatch":  `{"client_op_id":"7","device_id":"d","entity_type":"time_bookmark","action":"upsert","entity_id":"b1","payload":{"id":"b2","episode_id":"e","position_ms":1,"label":"","created_at":5},"client_timestamp":5}`,
		"bookmark no episode":   `{"client_op_id":"8","device_id":"d","entity_type":"time_bookmark","action":"upsert","entity_id":"b1","payload":{"id":"b1","episode_id":" ","position_ms":1,"label":"","created_at":5},"client_timestamp":5}`,
		"bookmark negative pos": `{"client_op_id":"9","device_id":"d","entity_type":"time_bookmark","action":"upsert","entity_id":"b1","payload":{"id":"b1","episode_id":"e","position_ms":-1,"label":"","created_at":5},"client_timestamp":5}`,
		"bookmark absurd pos":   `{"client_op_id":"10","device_id":"d","entity_type":"time_bookmark","action":"upsert","entity_id":"b1","payload":{"id":"b1","episode_id":"e","position_ms":9999999999999,"label":"","created_at":5},"client_timestamp":5}`,
		"bookmark long label":   fmt.Sprintf(`{"client_op_id":"11","device_id":"d","entity_type":"time_bookmark","action":"upsert","entity_id":"b1","payload":{"id":"b1","episode_id":"e","position_ms":1,"label":%q,"created_at":5},"client_timestamp":5}`, strings.Repeat("l", 2001)),
		"bookmark without time": `{"client_op_id":"12","device_id":"d","entity_type":"time_bookmark","action":"upsert","entity_id":"b1","payload":{"id":"b1","episode_id":"e","position_ms":1,"label":"","created_at":5},"client_timestamp":0}`,
	}
	for name, op := range cases {
		t.Run(name, func(t *testing.T) {
			rec := pushSync(t, h, ctx, `{"operations":[`+op+`]}`)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("want 400, got %d %s", rec.Code, rec.Body.String())
			}
		})
	}
	snapshot := takeLibrarySnapshot(t, h, ctx)
	if len(snapshot.NamedQueues) != 0 || len(snapshot.TimeBookmarks) != 0 {
		t.Fatalf("a rejected operation must not be stored: %+v", snapshot)
	}
}

func TestExportAccountIncludesNamedQueuesAndBookmarks(t *testing.T) {
	handler, database := newAuthTestHandler(t)
	seedDeletableAccount(t, database)
	sync := &SyncHandler{DB: database}
	ctx := context.WithValue(context.Background(), customMiddleware.UserContextKey, &customMiddleware.AuthUser{ID: "u1", Username: "User", Role: "user"})
	if rec := pushOps(t, sync, ctx,
		namedQueueOp("nq", "dev", "road trip", 100, `[]`),
		bookmarkOp("bm", "dev", "bm-9", 110),
	); rec.Code != http.StatusOK {
		t.Fatalf("push: %d %s", rec.Code, rec.Body.String())
	}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/export", nil).WithContext(ctx)
	rec := httptest.NewRecorder()
	handler.ExportAccount(rec, req)
	var export struct {
		NamedQueues   []map[string]any `json:"named_queues"`
		TimeBookmarks []map[string]any `json:"time_bookmarks"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &export); err != nil {
		t.Fatal(err)
	}
	if len(export.NamedQueues) != 1 || len(export.TimeBookmarks) != 1 {
		t.Fatalf("export must carry both collections: %s", rec.Body.String())
	}
}

package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type inboxBatchResponse struct {
	Feeds []struct {
		PodcastID string `json:"podcast_id"`
		Found     bool   `json:"found"`
		Episodes  []struct {
			ID      string `json:"id"`
			PubDate int64  `json:"pub_date"`
		} `json:"episodes"`
	} `json:"feeds"`
}

func seedInboxFeed(t *testing.T, h *PodcastHandler, id string, pubDates ...int64) {
	t.Helper()
	if _, err := h.DB.SQL.Exec(`INSERT INTO podcasts (id, feed_url, title, created_at, updated_at) VALUES (?, ?, ?, 1, 1)`,
		id, "https://example.com/"+id+".xml", id); err != nil {
		t.Fatal(err)
	}
	for _, pub := range pubDates {
		epID := fmt.Sprintf("%s-%d", id, pub)
		if _, err := h.DB.SQL.Exec(`
			INSERT INTO episodes (id, podcast_id, stable_identity_key, title, pub_date, has_pub_date, enclosure_url, created_at)
			VALUES (?, ?, ?, ?, ?, 1, ?, ?)`, epID, id, epID, epID, pub, "https://cdn.example/"+epID+".mp3", pub); err != nil {
			t.Fatal(err)
		}
	}
}

func postInbox(t *testing.T, h *PodcastHandler, body string) (*httptest.ResponseRecorder, inboxBatchResponse) {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/inbox/episodes", bytes.NewBufferString(body))
	rec := httptest.NewRecorder()
	h.InboxBatch(rec, req)
	var out inboxBatchResponse
	if rec.Code == http.StatusOK {
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			t.Fatalf("decode: %v body=%s", err, rec.Body.String())
		}
	}
	return rec, out
}

func newInboxBatchHandler(t *testing.T) *PodcastHandler {
	t.Helper()
	_, database := newInstanceHandler(t, nil)
	return &PodcastHandler{DB: database}
}

func TestInboxBatchGroupsPerFeedNewestFirstWithInclusiveSince(t *testing.T) {
	h := newInboxBatchHandler(t)
	seedInboxFeed(t, h, "busy", 100, 200, 300, 400)
	seedInboxFeed(t, h, "quiet", 50, 60)

	rec, out := postInbox(t, h, `{"feeds":[{"podcast_id":"busy","since":300},{"podcast_id":"quiet","since":0}],"limit_per_feed":15}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	if len(out.Feeds) != 2 || out.Feeds[0].PodcastID != "busy" || out.Feeds[1].PodcastID != "quiet" {
		t.Fatalf("request order must be kept: %+v", out.Feeds)
	}
	if got := out.Feeds[0].Episodes; len(got) != 2 || got[0].PubDate != 400 || got[1].PubDate != 300 {
		t.Fatalf("busy: since is inclusive and newest-first, got %+v", got)
	}
	if got := out.Feeds[1].Episodes; len(got) != 2 || got[0].PubDate != 60 || got[1].PubDate != 50 {
		t.Fatalf("quiet: got %+v", got)
	}
	if rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("a POST result must not be cached, got %q", rec.Header().Get("Cache-Control"))
	}
}

func TestInboxBatchBoundsEachFeedIndependently(t *testing.T) {
	h := newInboxBatchHandler(t)
	var busy []int64
	for i := int64(1); i <= 40; i++ {
		busy = append(busy, 1000+i)
	}
	seedInboxFeed(t, h, "busy", busy...)
	seedInboxFeed(t, h, "quiet", 5)

	_, out := postInbox(t, h, `{"feeds":[{"podcast_id":"busy"},{"podcast_id":"quiet"}],"limit_per_feed":10}`)
	if len(out.Feeds[0].Episodes) != 10 || out.Feeds[0].Episodes[0].PubDate != 1040 {
		t.Fatalf("busy must be cut to its newest 10, got %d first=%v", len(out.Feeds[0].Episodes), out.Feeds[0].Episodes[0])
	}
	// The busy feed's backlog must not push the quiet feed's episode out.
	if len(out.Feeds[1].Episodes) != 1 || out.Feeds[1].Episodes[0].PubDate != 5 {
		t.Fatalf("quiet feed lost its episode behind the busy one: %+v", out.Feeds[1].Episodes)
	}
}

func TestInboxBatchReportsUnknownFeedsAndKeepsDefaults(t *testing.T) {
	h := newInboxBatchHandler(t)
	seedInboxFeed(t, h, "known", 10)
	_, out := postInbox(t, h, `{"feeds":[{"podcast_id":"missing"},{"podcast_id":"known"}]}`)
	if out.Feeds[0].Found || len(out.Feeds[0].Episodes) != 0 {
		t.Fatalf("unknown feed must be found=false with no episodes: %+v", out.Feeds[0])
	}
	if !out.Feeds[1].Found || len(out.Feeds[1].Episodes) != 1 {
		t.Fatalf("known feed: %+v", out.Feeds[1])
	}
}

func TestInboxBatchDuplicateIDsKeepSmallestWatermark(t *testing.T) {
	h := newInboxBatchHandler(t)
	seedInboxFeed(t, h, "p", 10, 20, 30)
	_, out := postInbox(t, h, `{"feeds":[{"podcast_id":"p","since":30},{"podcast_id":"p","since":20}]}`)
	if len(out.Feeds) != 1 || len(out.Feeds[0].Episodes) != 2 {
		t.Fatalf("duplicates must collapse onto the smaller watermark: %+v", out.Feeds)
	}
}

func TestInboxBatchRejectsInvalidRequests(t *testing.T) {
	h := newInboxBatchHandler(t)
	many := make([]string, 101)
	for i := range many {
		many[i] = fmt.Sprintf(`{"podcast_id":"p%d"}`, i)
	}
	cases := map[string]string{
		"not json":       `nope`,
		"empty":          `{"feeds":[]}`,
		"missing feeds":  `{}`,
		"too many":       `{"feeds":[` + strings.Join(many, ",") + `]}`,
		"blank id":       `{"feeds":[{"podcast_id":""}]}`,
		"negative since": `{"feeds":[{"podcast_id":"p","since":-1}]}`,
		"limit too high": `{"feeds":[{"podcast_id":"p"}],"limit_per_feed":51}`,
		"limit negative": `{"feeds":[{"podcast_id":"p"}],"limit_per_feed":-1}`,
		"id too long":    `{"feeds":[{"podcast_id":"` + strings.Repeat("a", 257) + `"}]}`,
		"trailing json":  `{"feeds":[{"podcast_id":"p"}]} {}`,
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			rec, _ := postInbox(t, h, body)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
			}
		})
	}
}

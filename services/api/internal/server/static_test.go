package server

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStaticWebHandler(t *testing.T) {
	dir := t.TempDir()
	for name, content := range map[string]string{"index.html": "<html>shell</html>", "asset.txt": "public asset"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
	}
	handler := staticWebHandler(dir)
	for _, tc := range []struct {
		path   string
		status int
		body   string
	}{
		{"/", 200, "<html>shell</html>"}, {"/account", 200, "<html>shell</html>"},
		{"/asset.txt", 200, "public asset"}, {"/api/missing", 404, ""},
		{"/../secret.txt", 400, ""}, {"/..%5csecret.txt", 400, ""}, {"/C:/secret.txt", 400, ""},
	} {
		t.Run(tc.path, func(t *testing.T) {
			rec := httptest.NewRecorder()
			handler(rec, httptest.NewRequest(http.MethodGet, tc.path, nil))
			if rec.Code != tc.status || (tc.body != "" && rec.Body.String() != tc.body) {
				t.Fatalf("status=%d body=%q", rec.Code, rec.Body.String())
			}
		})
	}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/asset.txt", nil)
	req.Header.Set("Range", "bytes=0-5")
	handler(rec, req)
	if rec.Code != http.StatusPartialContent || rec.Body.String() != "public" {
		t.Fatalf("range response: %d %q", rec.Code, rec.Body.String())
	}
}

func TestStaticWebHandlerRejectsSymlinkEscape(t *testing.T) {
	parent := t.TempDir()
	web := filepath.Join(parent, "web")
	if err := os.Mkdir(web, 0700); err != nil {
		t.Fatal(err)
	}
	secret := filepath.Join(parent, "secret.txt")
	if err := os.WriteFile(secret, []byte("outside-secret"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(secret, filepath.Join(web, "linked.txt")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	rec := httptest.NewRecorder()
	staticWebHandler(web)(rec, httptest.NewRequest(http.MethodGet, "/linked.txt", nil))
	if rec.Code != http.StatusNotFound || strings.Contains(rec.Body.String(), "outside-secret") {
		t.Fatalf("symlink escaped root: %d %q", rec.Code, rec.Body.String())
	}
}

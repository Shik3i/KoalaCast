package server

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

func staticWebHandler(webDir string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"not found"}`))
			return
		}
		name := strings.TrimPrefix(r.URL.Path, "/")
		if name == "" {
			name = "index.html"
		}
		if !filepath.IsLocal(name) || strings.ContainsAny(name, "\\:") {
			http.Error(w, "invalid asset path", http.StatusBadRequest)
			return
		}
		// OpenInRoot prevents both lexical traversal and symlink escapes. Serve
		// the already-open handle, never reopen an unchecked request-derived path.
		file, err := os.OpenInRoot(webDir, name)
		fallback := os.IsNotExist(err)
		if err == nil {
			info, statErr := file.Stat()
			if statErr != nil {
				file.Close()
				http.NotFound(w, r)
				return
			}
			fallback = info.IsDir()
			if fallback {
				file.Close()
			}
		} else if !fallback {
			http.NotFound(w, r)
			return
		}
		if fallback {
			file, err = os.OpenInRoot(webDir, "index.html")
			if err != nil {
				http.NotFound(w, r)
				return
			}
		}
		defer file.Close()
		info, err := file.Stat()
		if err != nil || !info.Mode().IsRegular() {
			http.NotFound(w, r)
			return
		}
		if fallback || name == "index.html" {
			w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		} else if strings.HasPrefix(r.URL.Path, "/_app/immutable/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			switch strings.ToLower(filepath.Ext(name)) {
			case ".avif", ".jpg", ".jpeg", ".png", ".svg", ".webp", ".woff2":
				w.Header().Set("Cache-Control", "public, max-age=604800, stale-while-revalidate=86400")
			case ".txt", ".xml", ".webmanifest":
				w.Header().Set("Cache-Control", "public, max-age=3600, must-revalidate")
			}
		}
		http.ServeContent(w, r, info.Name(), info.ModTime(), file)
	}
}

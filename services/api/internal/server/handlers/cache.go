package handlers

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
)

// writePublicJSON gives clients a cheap freshness probe. A returning client can
// paint its local snapshot first and issue a conditional GET; unchanged data is
// answered with headers only.
func writePublicJSON(
	w http.ResponseWriter,
	r *http.Request,
	value any,
	cacheControl string,
) {
	body, err := json.Marshal(value)
	if err != nil {
		http.Error(w, `{"error":"failed to encode response"}`, http.StatusInternalServerError)
		return
	}
	sum := sha256.Sum256(body)
	etag := `"` + hex.EncodeToString(sum[:]) + `"`
	w.Header().Set("Cache-Control", cacheControl)
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("ETag", etag)
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(append(body, '\n'))
}

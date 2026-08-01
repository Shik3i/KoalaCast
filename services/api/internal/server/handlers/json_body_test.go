package handlers

import (
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDecodeLimitedJSON(t *testing.T) {
	t.Run("accepts one value", func(t *testing.T) {
		request := httptest.NewRequest("POST", "/", strings.NewReader(`{"enabled":true}`))
		var payload struct {
			Enabled bool `json:"enabled"`
		}
		if err := decodeLimitedJSON(httptest.NewRecorder(), request, 1024, &payload); err != nil {
			t.Fatalf("decode: %v", err)
		}
		if !payload.Enabled {
			t.Fatal("enabled was not decoded")
		}
	})

	t.Run("rejects an oversized body", func(t *testing.T) {
		request := httptest.NewRequest("POST", "/", strings.NewReader(`{"value":"`+strings.Repeat("x", 128)+`"}`))
		if err := decodeLimitedJSON(httptest.NewRecorder(), request, 32, &map[string]string{}); err == nil {
			t.Fatal("oversized body was accepted")
		}
	})

	t.Run("rejects a second value", func(t *testing.T) {
		request := httptest.NewRequest("POST", "/", strings.NewReader(`{} {}`))
		if err := decodeLimitedJSON(httptest.NewRecorder(), request, 1024, &map[string]string{}); err == nil {
			t.Fatal("multiple JSON values were accepted")
		}
	})
}

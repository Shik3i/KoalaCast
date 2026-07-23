package handlers

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/Shik3i/KoalaCast/services/api/internal/db"
)

type HealthHandler struct {
	DB *db.DB
}

type HealthResponse struct {
	Status    string    `json:"status"`
	Timestamp time.Time `json:"timestamp"`
	Version   string    `json:"version"`
}

type ReadinessResponse struct {
	Status   string `json:"status"`
	Database string `json:"database"`
}

func (h *HealthHandler) Healthz(w http.ResponseWriter, r *http.Request) {
	resp := HealthResponse{
		Status:    "ok",
		Timestamp: time.Now().UTC(),
		Version:   "1.0.0",
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(resp)
}

func (h *HealthHandler) Readyz(w http.ResponseWriter, r *http.Request) {
	dbStatus := "connected"
	statusCode := http.StatusOK

	if h.DB == nil || h.DB.SQL == nil || h.DB.SQL.Ping() != nil {
		dbStatus = "disconnected"
		statusCode = http.StatusServiceUnavailable
	}

	resp := ReadinessResponse{
		Status:   ifThenElse(statusCode == http.StatusOK, "ready", "not_ready"),
		Database: dbStatus,
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(resp)
}

func ifThenElse(cond bool, a, b string) string {
	if cond {
		return a
	}
	return b
}

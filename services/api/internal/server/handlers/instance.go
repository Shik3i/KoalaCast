package handlers

import (
	"encoding/json"
	"net/http"

	"github.com/Shik3i/KoalaCast/services/api/internal/config"
)

// InstanceHandler publishes who runs this deployment, so a client that points at
// a self-hosted server can show that operator's identity and legal links instead
// of the official instance's.
type InstanceHandler struct {
	Config *config.Config
	Auth   *AuthHandler
}

type instanceResponse struct {
	ServiceName         string `json:"service_name"`
	OperatorName        string `json:"operator_name,omitempty"`
	PrivacyPolicyURL    string `json:"privacy_policy_url,omitempty"`
	LegalNoticeURL      string `json:"legal_notice_url,omitempty"`
	RegistrationEnabled bool   `json:"registration_enabled"`
}

// Get returns the instance identity. Every value comes from validated server
// configuration; nothing is derived from the request.
func (h *InstanceHandler) Get(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=300")
	_ = json.NewEncoder(w).Encode(instanceResponse{
		ServiceName:         h.Config.ServiceName,
		OperatorName:        h.Config.OperatorName,
		PrivacyPolicyURL:    h.Config.PrivacyPolicyURL,
		LegalNoticeURL:      h.Config.LegalNoticeURL,
		RegistrationEnabled: h.Auth.IsRegistrationEnabled(r),
	})
}

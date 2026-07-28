package handlers

import (
	"encoding/json"
	"testing"
)

func TestValidateSyncOperationAcceptsQueueAndSettings(t *testing.T) {
	tests := []SyncPushOperation{
		{
			ClientOpID: "q:1", DeviceID: "device", EntityType: "queue",
			EntityID: "main", Action: "upsert", ClientTimestamp: 1,
			Payload: json.RawMessage(`{"items":[],"updated_at":1}`),
		},
		{
			ClientOpID: "s:1", DeviceID: "device", EntityType: "settings",
			EntityID: "global", Action: "upsert", ClientTimestamp: 1,
			Payload: json.RawMessage(`{"volume_boost":true,"updated_at":1}`),
		},
		{
			ClientOpID: "ps:show:1", DeviceID: "device", EntityType: "podcast_settings",
			EntityID: "show", Action: "upsert", ClientTimestamp: 1,
			Payload: json.RawMessage(`{"podcast_id":"show","updated_at":1}`),
		},
	}
	for index := range tests {
		if err := validateSyncOperation(&tests[index]); err != nil {
			t.Fatalf("operation %d rejected: %v", index, err)
		}
	}
}

func TestValidateSyncOperationRejectsMalformedPreferenceEntities(t *testing.T) {
	tests := []SyncPushOperation{
		{
			ClientOpID: "q:1", DeviceID: "device", EntityType: "queue",
			EntityID: "wrong", Action: "upsert", ClientTimestamp: 1,
			Payload: json.RawMessage(`{"items":[],"updated_at":1}`),
		},
		{
			ClientOpID: "s:1", DeviceID: "device", EntityType: "settings",
			EntityID: "global", Action: "delete", ClientTimestamp: 1,
			Payload: json.RawMessage(`{}`),
		},
		{
			ClientOpID: "ps:show:1", DeviceID: "device", EntityType: "podcast_settings",
			EntityID: "show", Action: "upsert", ClientTimestamp: 1,
			Payload: json.RawMessage(`{"podcast_id":"other","updated_at":1}`),
		},
	}
	for index := range tests {
		if err := validateSyncOperation(&tests[index]); err == nil {
			t.Fatalf("operation %d unexpectedly accepted", index)
		}
	}
}

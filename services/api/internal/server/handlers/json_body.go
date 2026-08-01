package handlers

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

// decodeLimitedJSON bounds small control-plane request bodies and rejects a
// second JSON value after the expected payload.
func decodeLimitedJSON(w http.ResponseWriter, r *http.Request, maxBytes int64, value any) error {
	return decodeLimitedJSONWithOptions(w, r, maxBytes, value, false)
}

func decodeLimitedJSONStrict(w http.ResponseWriter, r *http.Request, maxBytes int64, value any) error {
	return decodeLimitedJSONWithOptions(w, r, maxBytes, value, true)
}

func decodeLimitedJSONWithOptions(
	w http.ResponseWriter,
	r *http.Request,
	maxBytes int64,
	value any,
	strict bool,
) error {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBytes))
	if strict {
		decoder.DisallowUnknownFields()
	}
	if err := decoder.Decode(value); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("request body contains multiple JSON values")
		}
		return err
	}
	return nil
}

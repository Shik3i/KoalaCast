package spotify

import (
	"testing"
)

func TestSearchExclusives(t *testing.T) {
	tests := []struct {
		query    string
		expected string
	}{
		{"gemischtes hack", "Gemischtes Hack"},
		{"Felix Lobrecht", "Gemischtes Hack"},
		{"Böhmermann", "Fest & Flauschig"},
		{"Hobbylos", "Hobbylos"},
		{"Rogan", "The Joe Rogan Experience"},
		{"unknown podcast xyz", ""},
	}

	for _, tt := range tests {
		results := SearchExclusives(tt.query)
		if tt.expected == "" {
			if len(results) != 0 {
				t.Errorf("Expected 0 results for query %q, got %d", tt.query, len(results))
			}
		} else {
			if len(results) == 0 {
				t.Fatalf("Expected results for query %q, got 0", tt.query)
			}
			found := false
			for _, r := range results {
				if r.Title == tt.expected {
					found = true
					break
				}
			}
			if !found {
				t.Errorf("Expected %q in results for query %q, but was not found", tt.expected, tt.query)
			}
		}
	}
}

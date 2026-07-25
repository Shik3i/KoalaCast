package itunes

import "testing"

func TestGenreIDForCategory(t *testing.T) {
	cases := map[string]int{
		"Technology":        1318,
		"technology":        1318,
		"  News  ":          1489,
		"Business":          1321,
		"Science":           1533,
		"Comedy":            1303,
		"Society":           1324,
		"Society & Culture": 1324,
		"Arts":              1301,
		"Education":         1304,
		"Health & Fitness":  1512,
		"True Crime":        1488,
		"TV & Film":         1309,
		"All":               0, // overall chart
		"":                  0,
		"Nonsense":          0,
	}
	for input, want := range cases {
		if got := GenreIDForCategory(input); got != want {
			t.Errorf("GenreIDForCategory(%q) = %d, want %d", input, got, want)
		}
	}
}

func TestSanitizeRegion(t *testing.T) {
	cases := map[string]string{
		"us":     "us",
		"DE":     "de",
		" gb ":   "gb",
		"":       "us", // empty → default
		"usa":    "us", // wrong length → default
		"u1":     "us", // non-alpha → default
		"../etc": "us", // path-injection attempt → default
	}
	for input, want := range cases {
		if got := sanitizeRegion(input); got != want {
			t.Errorf("sanitizeRegion(%q) = %q, want %q", input, got, want)
		}
	}
}

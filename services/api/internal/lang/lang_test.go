package lang

import "testing"

func TestNormalize(t *testing.T) {
	cases := map[string]string{
		"de":      "de",
		"de-DE":   "de",
		"de_DE":   "de",
		"en-US":   "en",
		"PT-BR":   "pt",
		"  fr  ":  "fr",
		"":        "",
		"english": "",
		"e":       "",
		"d1":      "",
		"-de":     "",
	}
	for in, want := range cases {
		if got := Normalize(in); got != want {
			t.Errorf("Normalize(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestMatches(t *testing.T) {
	cases := []struct {
		name   string
		code   string
		wanted []string
		want   bool
	}{
		{"no filter shows everything", "en", nil, true},
		{"exact match", "de", []string{"de"}, true},
		{"regional tag matches bare code", "de-AT", []string{"de"}, true},
		{"non-match is filtered", "en", []string{"de"}, false},
		{"one of several", "fr", []string{"de", "fr"}, true},
		{"unknown language is kept", "", []string{"de"}, true},
		{"unparseable language is kept", "english", []string{"de"}, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Matches(c.code, c.wanted); got != c.want {
				t.Errorf("Matches(%q, %v) = %v, want %v", c.code, c.wanted, got, c.want)
			}
		})
	}
}

func TestParseList(t *testing.T) {
	cases := []struct {
		in   string
		want []string
	}{
		{"de,en", []string{"de", "en"}},
		{"de-DE, EN-us", []string{"de", "en"}},
		{"de,de,de", []string{"de"}},
		{"de,klingon,xx", []string{"de"}},
		{"", nil},
		{"   ", nil},
	}
	for _, c := range cases {
		got := ParseList(c.in)
		if len(got) != len(c.want) {
			t.Fatalf("ParseList(%q) = %v, want %v", c.in, got, c.want)
		}
		for i := range got {
			if got[i] != c.want[i] {
				t.Errorf("ParseList(%q)[%d] = %q, want %q", c.in, i, got[i], c.want[i])
			}
		}
	}
}

func TestDetect(t *testing.T) {
	cases := []struct {
		name string
		text string
		want string
	}{
		{
			"german show description",
			"Jede Woche sprechen wir über die Themen, die unser Leben bewegen. Eine neue Folge mit Gästen aus aller Welt.",
			"de",
		},
		{
			"english show description",
			"Every week we talk with the people behind the stories that shape your world. A new episode about how and why.",
			"en",
		},
		{
			"french show description",
			"Chaque semaine, nous vous proposons une émission avec des histoires qui racontent la vie des gens dans le monde.",
			"fr",
		},
		{
			"spanish show description",
			"Cada semana traemos un programa con las historias de la gente que cambia el mundo, pero también sobre la vida.",
			"es",
		},
		{
			"dutch show description",
			"Elke week een nieuwe aflevering met verhalen over de mensen die onze wereld veranderen. Niet te missen.",
			"nl",
		},
		{"empty text is unknown", "", ""},
		{"too short is unknown", "Podcast", ""},
		{"proper nouns only stay unknown", "Serial Radiolab Reply All", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Detect(c.text); got != c.want {
				t.Errorf("Detect(%q) = %q, want %q", c.text, got, c.want)
			}
		})
	}
}

// A distinctive character alone should not outvote a body of text that is
// clearly another language — "ß" in an English sentence must not flip it.
func TestDetectScriptHintDoesNotOverrideStrongSignal(t *testing.T) {
	text := "Every week we talk with the people behind the stories that shape your world, from Weißensee and beyond."
	if got := Detect(text); got != "en" {
		t.Errorf("Detect() = %q, want %q", got, "en")
	}
}

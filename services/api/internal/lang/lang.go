// Package lang resolves the spoken language of a podcast so Discover and Search
// can be filtered to the languages a listener actually understands.
//
// Language is deliberately distinct from the iTunes *storefront region*: the
// German storefront carries plenty of English shows, so filtering by region
// alone never gives a German-only chart. Feeds carry an RSS <language> tag and
// Podcast Index reports a language per feed, but the iTunes chart and search
// endpoints report none at all — for those, Detect falls back to a stopword
// heuristic over the title and description.
package lang

import (
	"strings"
	"unicode"
)

// Supported lists the language codes the client can filter by. Detection only
// ever returns one of these (or "" when it cannot tell).
var Supported = []string{"en", "de", "fr", "es", "it", "pt", "nl"}

// Normalize reduces an RSS/BCP-47 language tag to a bare lowercase primary
// subtag: "de-DE" -> "de", "en_US" -> "en", "PT-BR" -> "pt". Tags that are not
// two-letter primary subtags (or are unrecognized) return "".
func Normalize(tag string) string {
	tag = strings.ToLower(strings.TrimSpace(tag))
	if tag == "" {
		return ""
	}
	// Cut any region/script suffix: de-DE, de_DE, de-at, pt-br.
	if i := strings.IndexAny(tag, "-_"); i > 0 {
		tag = tag[:i]
	}
	if len(tag) != 2 {
		return ""
	}
	for _, r := range tag {
		if r < 'a' || r > 'z' {
			return ""
		}
	}
	return tag
}

// IsSupported reports whether code is one of the filterable languages.
func IsSupported(code string) bool {
	for _, s := range Supported {
		if s == code {
			return true
		}
	}
	return false
}

// Matches reports whether a podcast in language code should be shown to a
// listener who selected wanted. An empty wanted list means "no filter". An
// empty or unrecognized code means the language is unknown; unknown podcasts
// are kept, because dropping them would silently hide feeds whose publisher
// merely omitted the RSS <language> tag.
func Matches(code string, wanted []string) bool {
	if len(wanted) == 0 {
		return true
	}
	code = Normalize(code)
	if code == "" {
		return true
	}
	for _, w := range wanted {
		if Normalize(w) == code {
			return true
		}
	}
	return false
}

// ParseList turns a comma-separated "de,en" query parameter into normalized,
// deduplicated, supported language codes. Unknown entries are dropped.
func ParseList(raw string) []string {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	seen := make(map[string]bool)
	out := make([]string, 0, 4)
	for _, part := range strings.Split(raw, ",") {
		code := Normalize(part)
		if code == "" || !IsSupported(code) || seen[code] {
			continue
		}
		seen[code] = true
		out = append(out, code)
	}
	return out
}

// distinctive letters that essentially pin a language on sight.
var scriptHints = map[rune]string{
	'ß': "de",
	'ñ': "es",
	'¿': "es",
	'¡': "es",
	'ã': "pt",
	'õ': "pt",
	'ç': "fr", // also pt; French is far more common in podcast metadata
	'œ': "fr",
	'ĳ': "nl",
}

// stopwords are high-frequency function words per language. Words shared across
// several languages ("de" in French/Spanish, "in" in English/German/Dutch) are
// deliberately omitted — they add noise without separating anything.
var stopwords = map[string][]string{
	"en": {
		"the", "and", "with", "your", "you", "this", "that", "from", "about",
		"every", "week", "weekly", "show", "episode", "episodes", "host",
		"hosts", "guest", "guests", "talk", "talks", "stories", "story",
		"world", "life", "people", "what", "how", "why", "our", "we", "their",
	},
	"de": {
		"und", "der", "die", "das", "ist", "nicht", "mit", "für", "auch",
		"wir", "sich", "ein", "eine", "einen", "einem", "dem", "den", "aber",
		"oder", "wie", "was", "über", "bei", "nach", "wenn", "alles", "mehr",
		"jede", "jeden", "woche", "wöchentlich", "folge", "folgen", "sendung",
		"gespräch", "geschichten", "leben", "menschen", "welt", "immer",
		"zusammen", "unser", "ihre", "sind", "wird", "werden", "hier",
	},
	"fr": {
		"les", "est", "une", "pour", "dans", "avec", "vous", "nous", "cette",
		"sur", "plus", "tout", "tous", "chaque", "semaine", "émission",
		"épisode", "épisodes", "histoire", "histoires", "monde", "vie",
		"gens", "qui", "que", "mais", "aussi", "leur", "sont", "être",
		"toutes", "notre",
	},
	"es": {
		"los", "las", "una", "para", "con", "que", "por", "más", "todo",
		"todos", "cada", "semana", "programa", "episodio", "episodios",
		"historia", "historias", "mundo", "vida", "gente", "pero", "también",
		"su", "sus", "son", "ser", "nuestro", "nuestra", "donde", "cuando",
	},
	"it": {
		"gli", "una", "per", "con", "che", "più", "tutto", "tutti", "ogni",
		"settimana", "puntata", "puntate", "storia", "storie", "mondo",
		"vita", "gente", "ma", "anche", "sono", "essere", "nostro", "nostra",
		"dove", "quando", "delle", "degli", "nella", "nel",
	},
	"pt": {
		"uma", "para", "com", "que", "mais", "tudo", "todos", "cada",
		"semana", "programa", "episódio", "episódios", "história",
		"histórias", "mundo", "vida", "gente", "mas", "também", "são",
		"nosso", "nossa", "onde", "quando", "não", "você", "sobre", "dos",
		"das", "nas", "nos",
	},
	"nl": {
		"het", "een", "van", "voor", "met", "niet", "ook", "maar", "over",
		"elke", "week", "wekelijks", "aflevering", "afleveringen", "verhaal",
		"verhalen", "wereld", "leven", "mensen", "onze", "hun", "zijn",
		"wordt", "worden", "hier", "waar", "wanneer", "deze", "dit",
	},
}

// stopwordIndex maps a word to the languages it belongs to, built once at init
// so Detect is a single pass over the text rather than a scan per language.
var stopwordIndex = func() map[string][]string {
	idx := make(map[string][]string, 512)
	for code, words := range stopwords {
		for _, w := range words {
			idx[w] = append(idx[w], code)
		}
	}
	return idx
}()

// Detect guesses the language of free text (a podcast title plus description)
// using stopword frequency and distinctive characters. It returns a supported
// language code, or "" when the text is too short or too ambiguous to call.
//
// This is a heuristic, not a classifier: it exists only because the iTunes
// chart and search APIs return no language at all. Whenever an authoritative
// language is available — the RSS <language> tag or Podcast Index — prefer it.
func Detect(text string) string {
	text = strings.ToLower(strings.TrimSpace(text))
	if len(text) < 12 {
		return ""
	}

	scores := make(map[string]float64, len(Supported))

	// Distinctive characters carry more weight than any single stopword, since
	// a lone "ß" or "ñ" is already near-conclusive.
	for _, r := range text {
		if code, ok := scriptHints[r]; ok {
			scores[code] += 2.5
		}
	}

	words := strings.FieldsFunc(text, func(r rune) bool {
		return !unicode.IsLetter(r)
	})
	if len(words) < 3 {
		return ""
	}
	for _, w := range words {
		codes, ok := stopwordIndex[w]
		if !ok {
			continue
		}
		// A word shared by several languages splits its weight between them.
		weight := 1.0 / float64(len(codes))
		for _, c := range codes {
			scores[c] += weight
		}
	}

	best, runnerUp := "", 0.0
	bestScore := 0.0
	for code, s := range scores {
		if s > bestScore {
			best, runnerUp, bestScore = code, bestScore, s
			continue
		}
		if s > runnerUp {
			runnerUp = s
		}
	}

	// Require both an absolute floor and a clear margin over the second place,
	// so ambiguous text stays unknown (and therefore visible) instead of being
	// misfiled into a language the listener filtered out.
	if bestScore < 2.0 || bestScore < runnerUp*1.5 {
		return ""
	}
	return best
}

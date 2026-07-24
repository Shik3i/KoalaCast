package spotify

import (
	"strings"
)

type ExclusivePodcast struct {
	ID          string   `json:"id"`
	Title       string   `json:"title"`
	Author      string   `json:"author"`
	Description string   `json:"description"`
	SpotifyURL  string   `json:"spotify_url"`
	ArtworkURL  string   `json:"artwork_url"`
	Keywords    []string `json:"-"`
}

var KnownExclusives = []ExclusivePodcast{
	// --- German Spotify Originals & Exclusives ---
	{
		ID:          "spotify-gemischtes-hack",
		Title:       "Gemischtes Hack",
		Author:      "Felix Lobrecht & Tommi Schmitt",
		Description: "Der erfolgreichste Podcast Deutschlands mit Felix Lobrecht und Tommi Schmitt. Exklusiv auf Spotify verfügbar.",
		SpotifyURL:  "https://open.spotify.com/show/0vE914WkCstnJg3zH106Zc",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a3a2e379430c6a51206f6e8df",
		Keywords:    []string{"gemischtes hack", "felix lobrecht", "tommi schmitt", "hack"},
	},
	{
		ID:          "spotify-fest-und-flauschig",
		Title:       "Fest & Flauschig",
		Author:      "Jan Böhmermann & Olli Schulz",
		Description: "Der Podcast-Klassiker mit Jan Böhmermann und Olli Schulz. Jeden Mittwoch und Sonntag exklusiv auf Spotify.",
		SpotifyURL:  "https://open.spotify.com/show/1OLcQ52ugOGnvwq9Czep0T",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a7e2e379430c6a51206f6e8e0",
		Keywords:    []string{"fest und flauschig", "fest & flauschig", "böhmermann", "olli schulz", "sanft und sorgfältig"},
	},
	{
		ID:          "spotify-hobbylos",
		Title:       "Hobbylos",
		Author:      "Rezo & Julien Bam",
		Description: "Der Podcast von Julien Bam und Rezo. 5 Sterne vergeben und exklusiv auf Spotify streamen.",
		SpotifyURL:  "https://open.spotify.com/show/7B9Z44410aA5J6V0bXb1j8",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a5b2e379430c6a51206f6e8e1",
		Keywords:    []string{"hobbylos", "rezo", "julien bam", "julien"},
	},
	{
		ID:          "spotify-kaulitz-hills",
		Title:       "Kaulitz Hills – Senf aus Hollywood",
		Author:      "Bill & Tom Kaulitz",
		Description: "Die Tokio-Hotel-Zwillinge Bill und Tom Kaulitz servieren wöchentlich ihren Senf aus Hollywood.",
		SpotifyURL:  "https://open.spotify.com/show/6H4F7L6X1j8V9yZ1",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a9e2e379430c6a51206f6e8e2",
		Keywords:    []string{"kaulitz hills", "bill kaulitz", "tom kaulitz", "tokio hotel", "kaulitz"},
	},
	{
		ID:          "spotify-dick-und-doof",
		Title:       "Dick & Doof",
		Author:      "LaserLuca & Sandra",
		Description: "LaserLuca und SelfiesSandra quatschen über die absurdsten Geschichten aus ihrem Alltag.",
		SpotifyURL:  "https://open.spotify.com/show/3D5r6V660",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a3a2e379430c6a51206f6e8e5",
		Keywords:    []string{"dick und doof", "dick & doof", "laserluca", "selfiessandra", "luca"},
	},
	{
		ID:          "spotify-kalk-und-welk",
		Title:       "Kalk & Welk",
		Author:      "Oliver Kalkofe & Oliver Welker",
		Description: "Die beiden Format-Giganten Oliver Kalkofe und Oliver Welker lassen die fabelhafte Welt des Fernsehens und der Medien Revue passieren.",
		SpotifyURL:  "https://open.spotify.com/show/5V6G7H8",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a4a2e379430c6a51206f6e8e6",
		Keywords:    []string{"kalk und welk", "kalk & welk", "kalkofe", "welker", "oliver kalkofe"},
	},
	{
		ID:          "spotify-lanz-und-precht",
		Title:       "Lanz & Precht",
		Author:      "Markus Lanz & Richard David Precht",
		Description: "Markus Lanz und Richard David Precht reflektieren gesellschaftliche, politische und philosophische Fragen unserer Zeit.",
		SpotifyURL:  "https://open.spotify.com/show/5U1O6M9k1",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a8a2e379430c6a51206f6e8e7",
		Keywords:    []string{"lanz und precht", "lanz & precht", "markus lanz", "precht", "richard david precht"},
	},
	{
		ID:          "spotify-weird-crimes",
		Title:       "Weird Crimes",
		Author:      "Visa Vie & Ines Anioli",
		Description: "Visa Vie erzählt Ines Anioli die bizarrsten, verrücktesten und wahnwitzigsten True-Crime-Fälle der Geschichte.",
		SpotifyURL:  "https://open.spotify.com/show/6J4K9L1",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a7a2e379430c6a51206f6e8e8",
		Keywords:    []string{"weird crimes", "visa vie", "ines anioli", "true crime"},
	},
	{
		ID:          "spotify-drinnies",
		Title:       "Drinnies",
		Author:      "Giulia Becker & Chris Sommer",
		Description: "Der Podcast aus der Komfortzone: Giulia Becker und Chris Sommer berichten wöchentlich aus der Perspektive echter Drinnies.",
		SpotifyURL:  "https://open.spotify.com/show/2K8L9M1",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a6a2e379430c6a51206f6e8e9",
		Keywords:    []string{"drinnies", "giulia becker", "chris sommer"},
	},
	{
		ID:          "spotify-bratwurst-und-baklava",
		Title:       "Bratwurst und Baklava",
		Author:      "Bastian Bielendorfer & Özcan Cosar",
		Description: "Bastian Bielendorfer und Özcan Cosar verbinden deutsches Lehrerkind-Tum mit türkischem Kulturreichtum.",
		SpotifyURL:  "https://open.spotify.com/show/4M9N0P2",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a5a2e379430c6a51206f6e8ea",
		Keywords:    []string{"bratwurst und baklava", "bastian bielendorfer", "özcan cosar", "bielendorfer"},
	},

	// --- International Spotify Originals & Exclusives ---
	{
		ID:          "spotify-joe-rogan",
		Title:       "The Joe Rogan Experience",
		Author:      "Joe Rogan",
		Description: "The official podcast of comedian and UFC commentator Joe Rogan, featuring long-form conversations with scientists, artists, and experts.",
		SpotifyURL:  "https://open.spotify.com/show/4rOoJ6Egrf8K2IrywzwOMW",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a1a2e379430c6a51206f6e8e3",
		Keywords:    []string{"joe rogan", "rogan", "jre", "joe rogan experience"},
	},
	{
		ID:          "spotify-call-her-daddy",
		Title:       "Call Her Daddy",
		Author:      "Alex Cooper",
		Description: "Alex Cooper's Call Her Daddy is one of the most listened-to podcasts in the world, featuring candid conversations and celebrity interviews.",
		SpotifyURL:  "https://open.spotify.com/show/3r5Us823",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a2a2e379430c6a51206f6e8e4",
		Keywords:    []string{"call her daddy", "alex cooper", "chd"},
	},
	{
		ID:          "spotify-emma-chamberlain",
		Title:       "Anything Goes with Emma Chamberlain",
		Author:      "Emma Chamberlain",
		Description: "Emma Chamberlain talks about everything and anything from the comfort of her bed or intimate settings.",
		SpotifyURL:  "https://open.spotify.com/show/5Pox9a6K",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a4a2e379430c6a51206f6e8eb",
		Keywords:    []string{"emma chamberlain", "anything goes", "chamberlain"},
	},
	{
		ID:          "spotify-armchair-expert",
		Title:       "Armchair Expert with Dax Shepard",
		Author:      "Dax Shepard & Monica Padman",
		Description: "Dax Shepard celebrates the messiness of being human through candid, deep interviews with high-profile guests.",
		SpotifyURL:  "https://open.spotify.com/show/6Pox9a7L",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a3a2e379430c6a51206f6e8ec",
		Keywords:    []string{"armchair expert", "dax shepard", "monica padman"},
	},
	{
		ID:          "spotify-serial-killers",
		Title:       "Serial Killers",
		Author:      "Parcast / Spotify Studios",
		Description: "A psychological and entertaining look into the minds, methods, and madness of history's most notorious serial killers.",
		SpotifyURL:  "https://open.spotify.com/show/7Pox9a8M",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a2a2e379430c6a51206f6e8ed",
		Keywords:    []string{"serial killers", "parcast", "spotify studios", "true crime"},
	},
	{
		ID:          "spotify-conspiracy-theories",
		Title:       "Conspiracy Theories",
		Author:      "Parcast / Spotify Studios",
		Description: "Investigating the complicated stories behind history's most controversial events and high-stakes conspiracies.",
		SpotifyURL:  "https://open.spotify.com/show/8Pox9a9N",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a1a2e379430c6a51206f6e8ee",
		Keywords:    []string{"conspiracy theories", "parcast", "conspiracies"},
	},
	{
		ID:          "spotify-heavyweight",
		Title:       "Heavyweight",
		Author:      "Jonathan Goldstein / Gimlet",
		Description: "Jonathan Goldstein helps guests journey back to pivotal moments in their past to resolve long-standing regrets and questions.",
		SpotifyURL:  "https://open.spotify.com/show/9Pox9b0O",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a0a2e379430c6a51206f6e8ef",
		Keywords:    []string{"heavyweight", "jonathan goldstein", "gimlet"},
	},
	{
		ID:          "spotify-science-vs",
		Title:       "Science Vs",
		Author:      "Wendy Zukerman / Gimlet",
		Description: "Science Vs takes on fads, trends, and opinionated ideas to find out what's fact, what's myth, and what's in between.",
		SpotifyURL:  "https://open.spotify.com/show/0Pox9b1P",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a9a2e379430c6a51206f6e8f0",
		Keywords:    []string{"science vs", "wendy zukerman", "gimlet", "science"},
	},
	{
		ID:          "spotify-batman-unburied",
		Title:       "Batman Unburied",
		Author:      "Spotify / DC Comics / David S. Goyer",
		Description: "A psychological thriller audio drama taking listeners deep into the mind of Bruce Wayne as he confronts Gotham's darkest nightmare.",
		SpotifyURL:  "https://open.spotify.com/show/1Pox9b2Q",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a8a2e379430c6a51206f6e8f1",
		Keywords:    []string{"batman unburied", "batman", "dc comics", "spotify original"},
	},
	{
		ID:          "spotify-dissect",
		Title:       "Dissect",
		Author:      "Cole Cuchna / Spotify Studios",
		Description: "A serialized music podcast that analyzes one iconic album per season, line by line, song by song.",
		SpotifyURL:  "https://open.spotify.com/show/2Pox9b3R",
		ArtworkURL:  "https://i.scdn.co/image/ab6765630000ba8a7a2e379430c6a51206f6e8f2",
		Keywords:    []string{"dissect", "cole cuchna", "hip hop analysis", "music analysis"},
	},
}

// SearchExclusives matches a search query against known Spotify Exclusive podcasts.
func SearchExclusives(query string) []ExclusivePodcast {
	q := strings.ToLower(strings.TrimSpace(query))
	if q == "" {
		return nil
	}

	var matches []ExclusivePodcast
	for _, ep := range KnownExclusives {
		// Check title, author, or keywords match
		if strings.Contains(strings.ToLower(ep.Title), q) ||
			strings.Contains(strings.ToLower(ep.Author), q) {
			matches = append(matches, ep)
			continue
		}

		for _, kw := range ep.Keywords {
			if strings.Contains(kw, q) || strings.Contains(q, kw) {
				matches = append(matches, ep)
				break
			}
		}
	}
	return matches
}

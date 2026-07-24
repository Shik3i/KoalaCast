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

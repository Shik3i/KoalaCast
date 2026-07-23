package rss

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"
)

type ParsedFeed struct {
	Title       string
	Description string
	Author      string
	Link        string
	Language    string
	Copyright   string
	ArtworkURL  string
	Explicit    bool
	Episodes    []ParsedEpisode
}

type ParsedEpisode struct {
	GUID            string
	FallbackHash    string
	Title           string
	Description     string
	PubDate         time.Time
	DurationMS      int64 // Stored strictly in integer milliseconds
	EnclosureURL    string
	EnclosureType   string
	EnclosureLength int64
	ArtworkURL      string
	EpisodeNumber   int
	SeasonNumber    int
	Explicit        bool
	Link            string
	ChaptersURL     string
	TranscriptURL   string
}

// RSS XML structs
type rssDocument struct {
	XMLName xml.Name   `xml:"rss"`
	Channel rssChannel `xml:"channel"`
}

type rssChannel struct {
	Title       string    `xml:"title"`
	Link        string    `xml:"link"`
	Description string    `xml:"description"`
	Language    string    `xml:"language"`
	Copyright   string    `xml:"copyright"`
	Author      string    `xml:"author"`
	ItunesAuthor string   `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd author"`
	ItunesImage struct {
		Href string `xml:"href,attr"`
	} `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd image"`
	ItunesExplicit string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd explicit"`
	Items          []rssItem `xml:"item"`
}

type rssItem struct {
	Title       string `xml:"title"`
	GUID        rssGUID `xml:"guid"`
	PubDate     string `xml:"pubDate"`
	Description string `xml:"description"`
	Link        string `xml:"link"`
	Enclosure   struct {
		URL    string `xml:"url,attr"`
		Type   string `xml:"type,attr"`
		Length int64  `xml:"length,attr"`
	} `xml:"enclosure"`
	ItunesDuration string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd duration"`
	ItunesEpisode  int    `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd episode"`
	ItunesSeason   int    `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd season"`
	ItunesExplicit string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd explicit"`
	ItunesImage    struct {
		Href string `xml:"href,attr"`
	} `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd image"`
	PodcastChapters struct {
		URL string `xml:"url,attr"`
	} `xml:"https://podcastindex.org/podcast1.0 chapters"`
	PodcastTranscript struct {
		URL string `xml:"url,attr"`
	} `xml:"https://podcastindex.org/podcast1.0 transcript"`
}

type rssGUID struct {
	Value string `xml:",chardata"`
}

func ParseFeedXML(r io.Reader) (*ParsedFeed, error) {
	decoder := xml.NewDecoder(r)
	var doc rssDocument
	if err := decoder.Decode(&doc); err != nil {
		return nil, fmt.Errorf("failed to parse RSS XML: %w", err)
	}

	ch := doc.Channel
	author := ch.ItunesAuthor
	if author == "" {
		author = ch.Author
	}

	explicit := strings.EqualFold(ch.ItunesExplicit, "yes") || strings.EqualFold(ch.ItunesExplicit, "true")

	feed := &ParsedFeed{
		Title:       strings.TrimSpace(ch.Title),
		Description: strings.TrimSpace(ch.Description),
		Author:      strings.TrimSpace(author),
		Link:        strings.TrimSpace(ch.Link),
		Language:    strings.TrimSpace(ch.Language),
		Copyright:   strings.TrimSpace(ch.Copyright),
		ArtworkURL:  strings.TrimSpace(ch.ItunesImage.Href),
		Explicit:    explicit,
	}

	for _, item := range ch.Items {
		guid := strings.TrimSpace(item.GUID.Value)
		pubDate := parseRFC822(item.PubDate)
		durationMS := parseDurationToMS(item.ItunesDuration)

		fallbackRaw := fmt.Sprintf("%s|%s|%d", item.Enclosure.URL, item.Title, pubDate.Unix())
		hash := sha256.Sum256([]byte(fallbackRaw))
		fallbackHash := hex.EncodeToString(hash[:])

		epExplicit := strings.EqualFold(item.ItunesExplicit, "yes") || strings.EqualFold(item.ItunesExplicit, "true")

		ep := ParsedEpisode{
			GUID:            guid,
			FallbackHash:    fallbackHash,
			Title:           strings.TrimSpace(item.Title),
			Description:     strings.TrimSpace(item.Description),
			PubDate:         pubDate,
			DurationMS:      durationMS,
			EnclosureURL:    strings.TrimSpace(item.Enclosure.URL),
			EnclosureType:   strings.TrimSpace(item.Enclosure.Type),
			EnclosureLength: item.Enclosure.Length,
			ArtworkURL:      strings.TrimSpace(item.ItunesImage.Href),
			EpisodeNumber:   item.ItunesEpisode,
			SeasonNumber:    item.ItunesSeason,
			Explicit:        epExplicit,
			Link:            strings.TrimSpace(item.Link),
			ChaptersURL:     strings.TrimSpace(item.PodcastChapters.URL),
			TranscriptURL:   strings.TrimSpace(item.PodcastTranscript.URL),
		}

		feed.Episodes = append(feed.Episodes, ep)
	}

	return feed, nil
}

func parseDurationToMS(durationStr string) int64 {
	durationStr = strings.TrimSpace(durationStr)
	if durationStr == "" {
		return 0
	}

	// Case 1: Pure seconds string (e.g. "1800")
	if sec, err := strconv.ParseInt(durationStr, 10, 64); err == nil {
		return sec * 1000
	}

	// Case 2: HH:MM:SS or MM:SS format
	parts := strings.Split(durationStr, ":")
	if len(parts) == 3 {
		h, _ := strconv.ParseInt(parts[0], 10, 64)
		m, _ := strconv.ParseInt(parts[1], 10, 64)
		s, _ := strconv.ParseInt(parts[2], 10, 64)
		return ((h * 3600) + (m * 60) + s) * 1000
	} else if len(parts) == 2 {
		m, _ := strconv.ParseInt(parts[0], 10, 64)
		s, _ := strconv.ParseInt(parts[1], 10, 64)
		return ((m * 60) + s) * 1000
	}

	return 0
}

func parseRFC822(dateStr string) time.Time {
	dateStr = strings.TrimSpace(dateStr)
	if dateStr == "" {
		return time.Now()
	}

	formats := []string{
		time.RFC1123Z,
		time.RFC1123,
		time.RFC822Z,
		time.RFC822,
		"Mon, 2 Jan 2006 15:04:05 -0700",
		"Mon, 2 Jan 2006 15:04:05 MST",
	}

	for _, fmtStr := range formats {
		if t, err := time.Parse(fmtStr, dateStr); err == nil {
			return t
		}
	}

	return time.Now()
}

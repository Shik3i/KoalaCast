package rss

import (
	"bytes"
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
	FeedType       string // "rss" or "atom"
	Title          string
	Description    string
	Author         string
	Link           string
	Language       string
	Copyright      string
	ArtworkURL     string
	Explicit       bool
	FundingURL     string
	FundingText    string
	ValueType      string
	ValueMethod    string
	ValueRecipient string
	IsLive         bool
	LiveURL        string
	Episodes       []ParsedEpisode
}

type ParsedEpisode struct {
	GUID            string
	FallbackHash    string
	StableKey       string
	Title           string
	Description     string
	ContentEncoded  string
	PubDate         time.Time
	HasPubDate      bool
	DurationMS      int64 // Integer milliseconds
	EnclosureURL    string
	EnclosureType   string
	EnclosureLength int64
	ArtworkURL      string
	EpisodeNumber   int
	SeasonNumber    int
	EpisodeType     string
	Explicit        bool
	Link            string
	ChaptersURL     string
	Transcripts     []TranscriptRef
}

type TranscriptRef struct {
	URL  string
	Type string
}

// Structs for RSS 2.0
type rssDocument struct {
	XMLName xml.Name   `xml:"rss"`
	Channel rssChannel `xml:"channel"`
}

type rssChannel struct {
	Title        string `xml:"title"`
	Link         string `xml:"link"`
	Description  string `xml:"description"`
	Language     string `xml:"language"`
	Copyright    string `xml:"copyright"`
	Author       string `xml:"author"`
	ItunesAuthor string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd author"`
	ItunesImage  struct {
		Href string `xml:"href,attr"`
	} `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd image"`
	ItunesExplicit string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd explicit"`
	PodcastFunding struct {
		URL  string `xml:"url,attr"`
		Text string `xml:",chardata"`
	} `xml:"https://podcastindex.org/namespace/1.0 funding"`
	PodcastValue struct {
		Type       string `xml:"type,attr"`
		Method     string `xml:"method,attr"`
		Recipients []struct {
			Name    string `xml:"name,attr"`
			Type    string `xml:"type,attr"`
			Address string `xml:"address,attr"`
			Split   int    `xml:"split,attr"`
		} `xml:"https://podcastindex.org/namespace/1.0 valueRecipient"`
	} `xml:"https://podcastindex.org/namespace/1.0 value"`
	PodcastLiveItem struct {
		Status    string `xml:"status,attr"`
		Start     string `xml:"start,attr"`
		End       string `xml:"end,attr"`
		Enclosure struct {
			URL string `xml:"url,attr"`
		} `xml:"enclosure"`
	} `xml:"https://podcastindex.org/namespace/1.0 liveItem"`
	Items []rssItem `xml:"item"`
}

type rssItem struct {
	Title       string  `xml:"title"`
	GUID        rssGUID `xml:"guid"`
	PubDate     string  `xml:"pubDate"`
	Description string  `xml:"description"`
	Content     string  `xml:"http://purl.org/rss/1.0/modules/content/ encoded"`
	Link        string  `xml:"link"`
	Enclosure   struct {
		URL    string `xml:"url,attr"`
		Type   string `xml:"type,attr"`
		Length int64  `xml:"length,attr"`
	} `xml:"enclosure"`
	ItunesDuration    string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd duration"`
	ItunesEpisode     int    `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd episode"`
	ItunesSeason      int    `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd season"`
	ItunesEpisodeType string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd episodeType"`
	ItunesExplicit    string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd explicit"`
	ItunesImage       struct {
		Href string `xml:"href,attr"`
	} `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd image"`
	MediaContent []struct {
		Duration string `xml:"duration,attr"`
	} `xml:"http://search.yahoo.com/mrss/ content"`
	PodcastChapters struct {
		URL string `xml:"url,attr"`
	} `xml:"https://podcastindex.org/namespace/1.0 chapters"`
	PodcastTranscripts []struct {
		URL  string `xml:"url,attr"`
		Type string `xml:"type,attr"`
	} `xml:"https://podcastindex.org/namespace/1.0 transcript"`
}

type rssGUID struct {
	Value string `xml:",chardata"`
}

// Structs for Atom 1.0
type atomFeed struct {
	XMLName  xml.Name    `xml:"feed"`
	Title    string      `xml:"title"`
	Subtitle string      `xml:"subtitle"`
	Rights   string      `xml:"rights"`
	Links    []atomLink  `xml:"link"`
	Author   atomAuthor  `xml:"author"`
	Entries  []atomEntry `xml:"entry"`
}

type atomEntry struct {
	ID             string     `xml:"id"`
	Title          string     `xml:"title"`
	Summary        string     `xml:"summary"`
	Content        string     `xml:"http://www.w3.org/2005/Atom content"`
	Updated        string     `xml:"updated"`
	Published      string     `xml:"published"`
	Links          []atomLink `xml:"link"`
	Author         atomAuthor `xml:"author"`
	ItunesDuration string     `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd duration"`
	MediaContent   []struct {
		URL      string `xml:"url,attr"`
		Type     string `xml:"type,attr"`
		Length   int64  `xml:"fileSize,attr"`
		Duration string `xml:"duration,attr"`
	} `xml:"http://search.yahoo.com/mrss/ content"`
}

type atomLink struct {
	Rel    string `xml:"rel,attr"`
	Href   string `xml:"href,attr"`
	Type   string `xml:"type,attr"`
	Length int64  `xml:"length,attr"`
}

type atomAuthor struct {
	Name string `xml:"name"`
}

// ParseFeedXML detects whether the XML is RSS 2.0 or Atom 1.0 and parses metadata deterministically.
func ParseFeedXML(r io.Reader) (*ParsedFeed, error) {
	buf, err := io.ReadAll(r)
	if err != nil {
		return nil, fmt.Errorf("failed to read feed content: %w", err)
	}

	if detectFeedType(buf) == "atom" {
		return parseAtom(buf)
	}
	return parseRSS(buf)
}

// detectFeedType decides the feed dialect from the document's actual root
// element instead of substring-matching the whole payload. The old heuristic
// ("<feed" + the Atom namespace both appear somewhere) misfired on the very
// common case of an RSS feed that declares xmlns:atom for its <atom:link> self
// reference, or that merely contains the text "<feed" in show notes — routing a
// valid <rss> feed to the Atom parser and failing to ingest it. Defaults to
// "rss" when the root can't be determined.
func detectFeedType(buf []byte) string {
	dec := xml.NewDecoder(bytes.NewReader(buf))
	dec.Strict = false
	for {
		tok, err := dec.Token()
		if err != nil {
			return "rss"
		}
		if se, ok := tok.(xml.StartElement); ok {
			if strings.EqualFold(se.Name.Local, "feed") {
				return "atom"
			}
			return "rss"
		}
	}
}

func parseRSS(buf []byte) (*ParsedFeed, error) {
	var doc rssDocument
	if err := xml.Unmarshal(buf, &doc); err != nil {
		return nil, fmt.Errorf("failed to unmarshal RSS XML: %w", err)
	}

	ch := doc.Channel
	author := ch.ItunesAuthor
	if author == "" {
		author = ch.Author
	}

	explicit := strings.EqualFold(ch.ItunesExplicit, "yes") || strings.EqualFold(ch.ItunesExplicit, "true")

	var valRecipient string
	if len(ch.PodcastValue.Recipients) > 0 {
		valRecipient = ch.PodcastValue.Recipients[0].Address
	}

	isLive := strings.EqualFold(ch.PodcastLiveItem.Status, "live")

	feed := &ParsedFeed{
		FeedType:       "rss",
		Title:          strings.TrimSpace(ch.Title),
		Description:    strings.TrimSpace(ch.Description),
		Author:         strings.TrimSpace(author),
		Link:           strings.TrimSpace(ch.Link),
		Language:       strings.TrimSpace(ch.Language),
		Copyright:      strings.TrimSpace(ch.Copyright),
		ArtworkURL:     strings.TrimSpace(ch.ItunesImage.Href),
		Explicit:       explicit,
		FundingURL:     strings.TrimSpace(ch.PodcastFunding.URL),
		FundingText:    strings.TrimSpace(ch.PodcastFunding.Text),
		ValueType:      strings.TrimSpace(ch.PodcastValue.Type),
		ValueMethod:    strings.TrimSpace(ch.PodcastValue.Method),
		ValueRecipient: strings.TrimSpace(valRecipient),
		IsLive:         isLive,
		LiveURL:        strings.TrimSpace(ch.PodcastLiveItem.Enclosure.URL),
	}

	seenKeys := make(map[string]int)

	for _, item := range ch.Items {
		guid := strings.TrimSpace(item.GUID.Value)
		pubDate, hasPubDate := parseDate(item.PubDate)
		durationMS := parseDurationToMS(item.ItunesDuration)
		if durationMS == 0 && len(item.MediaContent) > 0 {
			durationMS = parseDurationToMS(item.MediaContent[0].Duration)
		}

		enclosureURL := strings.TrimSpace(item.Enclosure.URL)
		title := strings.TrimSpace(item.Title)

		// Stable Identity Resolution Rules
		stableKey := guid
		if stableKey == "" {
			if enclosureURL != "" {
				stableKey = "url:" + strings.ToLower(enclosureURL)
			} else {
				var dateUnix int64
				if hasPubDate {
					dateUnix = pubDate.Unix()
				}
				raw := fmt.Sprintf("%s|%s|%d", title, enclosureURL, dateUnix)
				h := sha256.Sum256([]byte(raw))
				stableKey = "hash:" + hex.EncodeToString(h[:])
			}
		}

		// Handle duplicate GUIDs within the same feed explicitly
		count := seenKeys[stableKey]
		seenKeys[stableKey] = count + 1
		if count > 0 {
			stableKey = fmt.Sprintf("%s#dup%d", stableKey, count)
		}

		fallbackRaw := fmt.Sprintf("%s|%s", title, enclosureURL)
		fbHash := sha256.Sum256([]byte(fallbackRaw))

		var transcripts []TranscriptRef
		for _, tr := range item.PodcastTranscripts {
			if tr.URL != "" {
				transcripts = append(transcripts, TranscriptRef{URL: tr.URL, Type: tr.Type})
			}
		}

		epExplicit := strings.EqualFold(item.ItunesExplicit, "yes") || strings.EqualFold(item.ItunesExplicit, "true")
		epType := item.ItunesEpisodeType
		if epType == "" {
			epType = "full"
		}

		ep := ParsedEpisode{
			GUID:            guid,
			FallbackHash:    hex.EncodeToString(fbHash[:]),
			StableKey:       stableKey,
			Title:           title,
			Description:     strings.TrimSpace(item.Description),
			ContentEncoded:  strings.TrimSpace(item.Content),
			PubDate:         pubDate,
			HasPubDate:      hasPubDate,
			DurationMS:      durationMS,
			EnclosureURL:    enclosureURL,
			EnclosureType:   strings.TrimSpace(item.Enclosure.Type),
			EnclosureLength: item.Enclosure.Length,
			ArtworkURL:      strings.TrimSpace(item.ItunesImage.Href),
			EpisodeNumber:   item.ItunesEpisode,
			SeasonNumber:    item.ItunesSeason,
			Explicit:        epExplicit,
			Link:            strings.TrimSpace(item.Link),
			ChaptersURL:     strings.TrimSpace(item.PodcastChapters.URL),
			Transcripts:     transcripts,
		}

		feed.Episodes = append(feed.Episodes, ep)
	}

	return feed, nil
}

func parseAtom(buf []byte) (*ParsedFeed, error) {
	var feedDoc atomFeed
	if err := xml.Unmarshal(buf, &feedDoc); err != nil {
		return nil, fmt.Errorf("failed to unmarshal Atom XML: %w", err)
	}

	var feedLink string
	for _, l := range feedDoc.Links {
		if l.Rel == "alternate" || l.Rel == "" {
			feedLink = l.Href
			break
		}
	}

	feed := &ParsedFeed{
		FeedType:    "atom",
		Title:       strings.TrimSpace(feedDoc.Title),
		Description: strings.TrimSpace(feedDoc.Subtitle),
		Author:      strings.TrimSpace(feedDoc.Author.Name),
		Link:        strings.TrimSpace(feedLink),
		Copyright:   strings.TrimSpace(feedDoc.Rights),
	}

	seenKeys := make(map[string]int)

	for _, entry := range feedDoc.Entries {
		id := strings.TrimSpace(entry.ID)
		dateStr := entry.Published
		if dateStr == "" {
			dateStr = entry.Updated
		}
		pubDate, hasPubDate := parseDate(dateStr)

		var enclosureURL, enclosureType, epLink string
		var enclosureLength int64

		for _, l := range entry.Links {
			if l.Rel == "enclosure" {
				enclosureURL = l.Href
				enclosureType = l.Type
				enclosureLength = l.Length
			} else if l.Rel == "alternate" || l.Rel == "" {
				epLink = l.Href
			}
		}
		if enclosureURL == "" {
			for _, media := range entry.MediaContent {
				if media.URL == "" {
					continue
				}
				enclosureURL = media.URL
				enclosureType = media.Type
				enclosureLength = media.Length
				break
			}
		}
		durationMS := parseDurationToMS(entry.ItunesDuration)
		if durationMS == 0 && len(entry.MediaContent) > 0 {
			durationMS = parseDurationToMS(entry.MediaContent[0].Duration)
		}

		title := strings.TrimSpace(entry.Title)
		desc := strings.TrimSpace(entry.Summary)
		content := strings.TrimSpace(entry.Content)

		stableKey := id
		if stableKey == "" {
			if enclosureURL != "" {
				stableKey = "url:" + strings.ToLower(enclosureURL)
			} else {
				var dateUnix int64
				if hasPubDate {
					dateUnix = pubDate.Unix()
				}
				raw := fmt.Sprintf("%s|%s|%d", title, enclosureURL, dateUnix)
				h := sha256.Sum256([]byte(raw))
				stableKey = "hash:" + hex.EncodeToString(h[:])
			}
		}

		count := seenKeys[stableKey]
		seenKeys[stableKey] = count + 1
		if count > 0 {
			stableKey = fmt.Sprintf("%s#dup%d", stableKey, count)
		}

		fallbackRaw := fmt.Sprintf("%s|%s", title, enclosureURL)
		fbHash := sha256.Sum256([]byte(fallbackRaw))

		ep := ParsedEpisode{
			GUID:            id,
			FallbackHash:    hex.EncodeToString(fbHash[:]),
			StableKey:       stableKey,
			Title:           title,
			Description:     desc,
			ContentEncoded:  content,
			PubDate:         pubDate,
			HasPubDate:      hasPubDate,
			DurationMS:      durationMS,
			EnclosureURL:    strings.TrimSpace(enclosureURL),
			EnclosureType:   strings.TrimSpace(enclosureType),
			EnclosureLength: enclosureLength,
			Link:            strings.TrimSpace(epLink),
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

	// Pure seconds. Some publishers emit fractional seconds.
	if sec, err := strconv.ParseInt(durationStr, 10, 64); err == nil {
		return sec * 1000
	}
	if sec, err := strconv.ParseFloat(durationStr, 64); err == nil && sec >= 0 {
		return int64(sec * 1000)
	}

	// HH:MM:SS or MM:SS format
	parts := strings.Split(durationStr, ":")
	if len(parts) == 3 {
		h, hErr := strconv.ParseInt(parts[0], 10, 64)
		m, mErr := strconv.ParseInt(parts[1], 10, 64)
		s, sErr := strconv.ParseFloat(parts[2], 64)
		if hErr != nil || mErr != nil || sErr != nil || h < 0 || m < 0 || s < 0 {
			return 0
		}
		return int64((float64(h*3600+m*60) + s) * 1000)
	} else if len(parts) == 2 {
		m, mErr := strconv.ParseInt(parts[0], 10, 64)
		s, sErr := strconv.ParseFloat(parts[1], 64)
		if mErr != nil || sErr != nil || m < 0 || s < 0 {
			return 0
		}
		return int64((float64(m*60) + s) * 1000)
	}

	return 0
}

func parseDate(dateStr string) (time.Time, bool) {
	dateStr = strings.TrimSpace(dateStr)
	if dateStr == "" {
		return time.Time{}, false // Zero time, deterministic
	}

	formats := []string{
		time.RFC3339,
		time.RFC3339Nano,
		time.RFC1123Z,
		time.RFC1123,
		time.RFC822Z,
		time.RFC822,
		"Mon, 2 Jan 2006 15:04:05 -0700",
		"Mon, 2 Jan 2006 15:04:05 MST",
		"2006-01-02T15:04:05Z",
		"2006-01-02",
	}

	for _, fmtStr := range formats {
		if t, err := time.Parse(fmtStr, dateStr); err == nil {
			return t, true
		}
	}

	return time.Time{}, false // Malformed date returns zero time
}

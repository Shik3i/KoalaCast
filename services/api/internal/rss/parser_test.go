package rss

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseFeedXML_SamplePodcast(t *testing.T) {
	fixturePath := filepath.Join("..", "..", "..", "..", "testdata", "feeds", "sample_podcast.xml")
	file, err := os.Open(fixturePath)
	if err != nil {
		t.Fatalf("failed to open fixture file: %v", err)
	}
	defer file.Close()

	feed, err := ParseFeedXML(file)
	if err != nil {
		t.Fatalf("ParseFeedXML failed: %v", err)
	}

	if feed.FeedType != "rss" {
		t.Errorf("expected FeedType 'rss', got '%s'", feed.FeedType)
	}
	if feed.Title != "KoalaCast Tech Talk" {
		t.Errorf("expected title 'KoalaCast Tech Talk', got '%s'", feed.Title)
	}

	if len(feed.Episodes) != 1 {
		t.Fatalf("expected 1 episode, got %d", len(feed.Episodes))
	}

	ep := feed.Episodes[0]
	if ep.Title != "Episode 1: Building a Privacy-First Podcast Player" {
		t.Errorf("unexpected episode title: %s", ep.Title)
	}
	if ep.DurationMS != 1800000 {
		t.Errorf("expected duration 1800000 ms, got %d ms", ep.DurationMS)
	}
	if !ep.HasPubDate {
		t.Errorf("expected HasPubDate to be true")
	}
}

func TestParseFeedXML_AtomFeed(t *testing.T) {
	atomXML := `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Atom Audio Feed</title>
  <subtitle>A sample Atom podcast feed</subtitle>
  <link rel="alternate" href="https://example.com/atom"/>
  <author><name>Atom Author</name></author>
  <entry>
    <id>urn:uuid:12345-atom-ep1</id>
    <title>Atom Episode 1</title>
    <summary>Atom episode summary</summary>
    <content>Full atom episode content</content>
    <published>2026-07-24T10:00:00Z</published>
    <link rel="enclosure" href="https://example.com/audio/atom1.mp3" type="audio/mpeg" length="123456"/>
  </entry>
</feed>`

	feed, err := ParseFeedXML(strings.NewReader(atomXML))
	if err != nil {
		t.Fatalf("ParseFeedXML Atom failed: %v", err)
	}

	if feed.FeedType != "atom" {
		t.Errorf("expected FeedType 'atom', got '%s'", feed.FeedType)
	}
	if feed.Title != "Atom Audio Feed" {
		t.Errorf("expected title 'Atom Audio Feed', got '%s'", feed.Title)
	}
	if len(feed.Episodes) != 1 {
		t.Fatalf("expected 1 episode, got %d", len(feed.Episodes))
	}

	ep := feed.Episodes[0]
	if ep.GUID != "urn:uuid:12345-atom-ep1" {
		t.Errorf("expected GUID 'urn:uuid:12345-atom-ep1', got '%s'", ep.GUID)
	}
	if ep.StableKey != "urn:uuid:12345-atom-ep1" {
		t.Errorf("expected StableKey 'urn:uuid:12345-atom-ep1', got '%s'", ep.StableKey)
	}
	if ep.EnclosureURL != "https://example.com/audio/atom1.mp3" {
		t.Errorf("unexpected enclosure URL: %s", ep.EnclosureURL)
	}
}

func TestParseFeedXML_MissingAndMalformedPubDate(t *testing.T) {
	xmlData := `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
  <channel>
    <title>No Date Feed</title>
    <item>
      <title>Episode No Date</title>
      <guid>ep-no-date</guid>
      <enclosure url="https://example.com/audio/nodate.mp3" type="audio/mpeg"/>
    </item>
    <item>
      <title>Episode Malformed Date</title>
      <pubDate>NOT_A_REAL_DATE_STRING</pubDate>
      <guid>ep-bad-date</guid>
      <enclosure url="https://example.com/audio/baddate.mp3" type="audio/mpeg"/>
    </item>
  </channel>
</rss>`

	feed, err := ParseFeedXML(strings.NewReader(xmlData))
	if err != nil {
		t.Fatalf("ParseFeedXML failed: %v", err)
	}

	if len(feed.Episodes) != 2 {
		t.Fatalf("expected 2 episodes, got %d", len(feed.Episodes))
	}

	ep1 := feed.Episodes[0]
	if ep1.HasPubDate {
		t.Errorf("expected HasPubDate=false for missing pubDate")
	}
	if !ep1.PubDate.IsZero() {
		t.Errorf("expected zero Time for missing pubDate, got %v", ep1.PubDate)
	}

	ep2 := feed.Episodes[1]
	if ep2.HasPubDate {
		t.Errorf("expected HasPubDate=false for malformed pubDate")
	}
	if !ep2.PubDate.IsZero() {
		t.Errorf("expected zero Time for malformed pubDate, got %v", ep2.PubDate)
	}
}

func TestParseFeedXML_DuplicateGUIDs(t *testing.T) {
	xmlData := `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
  <channel>
    <title>Duplicate GUID Feed</title>
    <item>
      <title>Part 1</title>
      <guid>same-guid</guid>
      <enclosure url="https://example.com/part1.mp3" type="audio/mpeg"/>
    </item>
    <item>
      <title>Part 2</title>
      <guid>same-guid</guid>
      <enclosure url="https://example.com/part2.mp3" type="audio/mpeg"/>
    </item>
  </channel>
</rss>`

	feed, err := ParseFeedXML(strings.NewReader(xmlData))
	if err != nil {
		t.Fatalf("ParseFeedXML failed: %v", err)
	}

	if len(feed.Episodes) != 2 {
		t.Fatalf("expected 2 episodes, got %d", len(feed.Episodes))
	}

	if feed.Episodes[0].StableKey != "same-guid" {
		t.Errorf("expected first episode StableKey 'same-guid', got '%s'", feed.Episodes[0].StableKey)
	}
	if feed.Episodes[1].StableKey != "same-guid#dup1" {
		t.Errorf("expected second episode StableKey 'same-guid#dup1', got '%s'", feed.Episodes[1].StableKey)
	}
}

func TestParseDurationToMS(t *testing.T) {
	tests := []struct {
		input    string
		expected int64
	}{
		{"1800", 1800000},
		{"00:30:00", 1800000},
		{"30:00", 1800000},
		{"01:15:30", 4530000},
		{"42.5", 42500},
		{"00:01:02.5", 62500},
		{"00:nope:10", 0},
		{"invalid", 0},
		{"", 0},
	}

	for _, tt := range tests {
		got := parseDurationToMS(tt.input)
		if got != tt.expected {
			t.Errorf("parseDurationToMS(%s) = %d, expected %d", tt.input, got, tt.expected)
		}
	}
}

func TestParseFeedXML_MediaDurationFallbacks(t *testing.T) {
	rssXML := `<?xml version="1.0"?>
<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
  <channel><title>Media RSS</title><item>
    <title>Media duration</title><guid>media-rss</guid>
    <enclosure url="https://example.com/rss.mp3" type="audio/mpeg"/>
    <media:content duration="123.5"/>
  </item></channel>
</rss>`
	feed, err := ParseFeedXML(strings.NewReader(rssXML))
	if err != nil {
		t.Fatalf("ParseFeedXML RSS failed: %v", err)
	}
	if got := feed.Episodes[0].DurationMS; got != 123500 {
		t.Fatalf("RSS media duration = %d, want 123500", got)
	}

	atomXML := `<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
  <title>Media Atom</title><entry>
    <id>media-atom</id><title>Media duration</title>
    <media:content url="https://example.com/atom.mp3" type="audio/mpeg" fileSize="42" duration="90"/>
  </entry>
</feed>`
	feed, err = ParseFeedXML(strings.NewReader(atomXML))
	if err != nil {
		t.Fatalf("ParseFeedXML Atom failed: %v", err)
	}
	episode := feed.Episodes[0]
	if episode.DurationMS != 90000 || episode.EnclosureURL != "https://example.com/atom.mp3" {
		t.Fatalf("unexpected Atom media fallback: %+v", episode)
	}
}

func TestParseFeedXML_MultipleTranscriptsAndContentEncoded(t *testing.T) {
	xmlData := `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:podcast="https://podcastindex.org/namespace/1.0">
  <channel>
    <title>Transcripts Feed</title>
    <item>
      <title>Episode With Transcripts</title>
      <guid>ep-transcripts</guid>
      <content:encoded><![CDATA[<p>Full HTML Content Description</p>]]></content:encoded>
      <enclosure url="https://example.com/audio/ep.mp3" type="audio/mpeg"/>
      <podcast:transcript url="https://example.com/t1.vtt" type="text/vtt"/>
      <podcast:transcript url="https://example.com/t2.srt" type="application/x-subrip"/>
    </item>
  </channel>
</rss>`

	feed, err := ParseFeedXML(strings.NewReader(xmlData))
	if err != nil {
		t.Fatalf("ParseFeedXML failed: %v", err)
	}

	ep := feed.Episodes[0]
	if ep.ContentEncoded != "<p>Full HTML Content Description</p>" {
		t.Errorf("unexpected content:encoded: %s", ep.ContentEncoded)
	}
	if len(ep.Transcripts) != 2 {
		t.Fatalf("expected 2 transcripts, got %d", len(ep.Transcripts))
	}
	if ep.Transcripts[0].URL != "https://example.com/t1.vtt" || ep.Transcripts[1].Type != "application/x-subrip" {
		t.Errorf("unexpected transcript details: %+v", ep.Transcripts)
	}
}

// Chapters were parsed but discarded before the chapters_url column existed, which
// left both clients' chapter UI unable to trigger. Pin the parse so it stays wired.
func TestParseFeedXML_Chapters(t *testing.T) {
	xmlData := `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
  <channel>
    <title>Chapters Feed</title>
    <item>
      <title>Episode With Chapters</title>
      <guid>ep-chapters</guid>
      <enclosure url="https://example.com/audio/ep.mp3" type="audio/mpeg"/>
      <podcast:chapters url="https://example.com/ep1/chapters.json" type="application/json+chapters"/>
    </item>
    <item>
      <title>Episode Without Chapters</title>
      <guid>ep-plain</guid>
      <enclosure url="https://example.com/audio/ep2.mp3" type="audio/mpeg"/>
    </item>
  </channel>
</rss>`

	feed, err := ParseFeedXML(strings.NewReader(xmlData))
	if err != nil {
		t.Fatalf("ParseFeedXML failed: %v", err)
	}
	if len(feed.Episodes) != 2 {
		t.Fatalf("expected 2 episodes, got %d", len(feed.Episodes))
	}
	if got := feed.Episodes[0].ChaptersURL; got != "https://example.com/ep1/chapters.json" {
		t.Errorf("chapters URL not parsed, got %q", got)
	}
	if got := feed.Episodes[1].ChaptersURL; got != "" {
		t.Errorf("expected no chapters URL on the plain episode, got %q", got)
	}
}

// itunes:episodeType is how a publisher marks a trailer or a bonus item, and it
// is what the Inbox's "hide specials" filter keys on. The parser computed the
// value and then left it out of the struct literal, so every episode ever
// ingested stored an empty type and the filter was reduced to guessing from the
// title.
func TestParseRSS_KeepsEpisodeType(t *testing.T) {
	feed := `<?xml version="1.0"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>Show</title>
    <item>
      <title>Season one is coming</title>
      <guid>ep-trailer</guid>
      <itunes:episodeType>trailer</itunes:episodeType>
    </item>
    <item>
      <title>Behind the scenes</title>
      <guid>ep-bonus</guid>
      <itunes:episodeType>Bonus</itunes:episodeType>
    </item>
    <item>
      <title>Episode one</title>
      <guid>ep-plain</guid>
    </item>
  </channel>
</rss>`

	parsed, err := ParseFeedXML(strings.NewReader(feed))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(parsed.Episodes) != 3 {
		t.Fatalf("expected 3 episodes, got %d", len(parsed.Episodes))
	}
	want := []string{"trailer", "Bonus", "full"}
	for i, expected := range want {
		if got := parsed.Episodes[i].EpisodeType; got != expected {
			t.Errorf("episode %d: EpisodeType = %q, want %q", i, got, expected)
		}
	}
}

// Atom has no equivalent tag, so an entry is a full episode rather than an
// untyped one — consumers compare against trailer/bonus.
func TestParseAtom_DefaultsEpisodeTypeToFull(t *testing.T) {
	feed := `<?xml version="1.0"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Show</title>
  <entry>
    <id>entry-1</id>
    <title>Episode one</title>
    <link rel="enclosure" href="https://cdn.example/a.mp3" type="audio/mpeg"/>
  </entry>
</feed>`

	parsed, err := ParseFeedXML(strings.NewReader(feed))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(parsed.Episodes) != 1 {
		t.Fatalf("expected 1 episode, got %d", len(parsed.Episodes))
	}
	if got := parsed.Episodes[0].EpisodeType; got != "full" {
		t.Errorf("EpisodeType = %q, want \"full\"", got)
	}
}

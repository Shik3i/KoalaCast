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

func TestParseFeedXML_MultipleTranscriptsAndContentEncoded(t *testing.T) {
	xmlData := `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:podcast="https://podcastindex.org/podcast1.0">
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

package rss

import (
	"net"
	"os"
	"path/filepath"
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

	// 1800 seconds -> 1800000 ms
	if ep.DurationMS != 1800000 {
		t.Errorf("expected duration 1800000 ms, got %d ms", ep.DurationMS)
	}
}

func TestParseFeedXML_Podcasting20Sample(t *testing.T) {
	fixturePath := filepath.Join("..", "..", "..", "..", "testdata", "feeds", "podcasting20_sample.xml")
	file, err := os.Open(fixturePath)
	if err != nil {
		t.Fatalf("failed to open fixture file: %v", err)
	}
	defer file.Close()

	feed, err := ParseFeedXML(file)
	if err != nil {
		t.Fatalf("ParseFeedXML failed: %v", err)
	}

	if len(feed.Episodes) != 1 {
		t.Fatalf("expected 1 episode, got %d", len(feed.Episodes))
	}

	ep := feed.Episodes[0]
	if ep.ChaptersURL != "https://example.com/chapters/p20_101.json" {
		t.Errorf("unexpected chapters URL: %s", ep.ChaptersURL)
	}

	if ep.TranscriptURL != "https://example.com/transcripts/p20_101.vtt" {
		t.Errorf("unexpected transcript URL: %s", ep.TranscriptURL)
	}
}

func TestIsIPBlocked(t *testing.T) {
	blockedIPs := []string{
		"127.0.0.1",
		"10.0.0.5",
		"172.16.0.100",
		"192.168.1.1",
		"169.254.169.254",
		"::1",
	}

	for _, ipStr := range blockedIPs {
		ip := net.ParseIP(ipStr)
		if !IsIPBlocked(ip) {
			t.Errorf("expected IP %s to be blocked", ipStr)
		}
	}

	publicIPs := []string{
		"8.8.8.8",
		"1.1.1.1",
		"140.82.121.4",
	}

	for _, ipStr := range publicIPs {
		ip := net.ParseIP(ipStr)
		if IsIPBlocked(ip) {
			t.Errorf("expected IP %s to be allowed", ipStr)
		}
	}
}

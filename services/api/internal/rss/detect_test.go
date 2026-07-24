package rss

import (
	"strings"
	"testing"
)

func TestDetectFeedType(t *testing.T) {
	cases := []struct {
		name string
		xml  string
		want string
	}{
		{
			name: "plain rss",
			xml:  `<?xml version="1.0"?><rss version="2.0"><channel><title>x</title></channel></rss>`,
			want: "rss",
		},
		{
			// The exact shape that used to misfire: RSS declaring the Atom
			// namespace for <atom:link>, and containing "<feed" in show notes.
			name: "rss with atom namespace and <feed in content",
			xml: `<?xml version="1.0"?>
				<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
				  <channel>
				    <atom:link href="https://example.com/feed" rel="self"/>
				    <title>News</title>
				    <item><title>Ep</title><description>see &lt;feed&gt; docs</description></item>
				  </channel>
				</rss>`,
			want: "rss",
		},
		{
			name: "rss preceded by stylesheet PI and whitespace",
			xml:  "<?xml version=\"1.0\"?>\n<?xml-stylesheet type=\"text/xsl\" href=\"/feed.xsl\"?>\n\n   <rss version=\"2.0\"><channel><title>x</title></channel></rss>",
			want: "rss",
		},
		{
			name: "real atom feed",
			xml:  `<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>x</title></feed>`,
			want: "atom",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := detectFeedType([]byte(tc.xml)); got != tc.want {
				t.Errorf("detectFeedType = %q, want %q", got, tc.want)
			}
		})
	}
}

// End-to-end: an RSS feed with the Atom namespace must parse as RSS (not error
// out through the Atom path) and yield its episodes.
func TestParseFeedXML_RSSWithAtomNamespace(t *testing.T) {
	feed := `<?xml version="1.0" encoding="UTF-8"?>
		<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
		  <channel>
		    <atom:link href="https://example.com/feed" rel="self" type="application/rss+xml"/>
		    <title>Raumzeit-like</title>
		    <item>
		      <title>Episode 1</title>
		      <enclosure url="https://example.com/ep1.m4a" type="audio/x-m4a" length="123"/>
		      <guid>ep1</guid>
		    </item>
		  </channel>
		</rss>`

	parsed, err := ParseFeedXML(strings.NewReader(feed))
	if err != nil {
		t.Fatalf("ParseFeedXML errored on RSS-with-atom-namespace feed: %v", err)
	}
	if parsed.FeedType != "rss" {
		t.Errorf("FeedType = %q, want \"rss\"", parsed.FeedType)
	}
	if len(parsed.Episodes) != 1 || parsed.Episodes[0].Title != "Episode 1" {
		t.Errorf("expected 1 episode titled 'Episode 1', got %+v", parsed.Episodes)
	}
}

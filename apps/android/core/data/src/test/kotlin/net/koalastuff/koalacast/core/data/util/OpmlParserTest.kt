package net.koalastuff.koalacast.core.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlParserTest {

    @Test
    fun parse_validOpml_extractsFeeds() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0">
              <head><title>Subscriptions</title></head>
              <body>
                <outline type="rss" text="Tech News Daily" title="Tech News Daily" xmlUrl="https://example.com/tech.xml" />
                <outline text="Science">
                  <outline type="rss" text="Space Talk" title="Space Talk" xmlUrl="https://example.com/space.xml" />
                </outline>
              </body>
            </opml>
        """.trimIndent()

        val feeds = OpmlParser.parse(xml)

        assertEquals(2, feeds.size)
        assertEquals("https://example.com/tech.xml", feeds[0].feedUrl)
        assertEquals("Tech News Daily", feeds[0].title)
        assertEquals("https://example.com/space.xml", feeds[1].feedUrl)
        assertEquals("Space Talk", feeds[1].title)
    }

    @Test
    fun parse_withBom_handlesBomGracefully() {
        val xml = "\uFEFF<?xml version=\"1.0\"?><opml version=\"2.0\"><body><outline text=\"Podcast\" xmlUrl=\"https://example.com/feed.xml\"/></body></opml>"

        val feeds = OpmlParser.parse(xml)

        assertEquals(1, feeds.size)
        assertEquals("https://example.com/feed.xml", feeds[0].feedUrl)
    }

    @Test
    fun parse_lowercaseXmlUrlAttribute_extractsFeed() {
        val xml = """<opml version="1.0"><body><outline text="My Podcast" xmlurl="https://example.com/rss.xml" /></body></opml>"""

        val feeds = OpmlParser.parse(xml)

        assertEquals(1, feeds.size)
        assertEquals("https://example.com/rss.xml", feeds[0].feedUrl)
        assertEquals("My Podcast", feeds[0].title)
    }

    @Test
    fun parse_emptyOrInvalidXml_returnsEmptyList() {
        assertTrue(OpmlParser.parse("").isEmpty())
        assertTrue(OpmlParser.parse("   ").isEmpty())
        assertTrue(OpmlParser.parse("not xml content").isEmpty())
    }
}

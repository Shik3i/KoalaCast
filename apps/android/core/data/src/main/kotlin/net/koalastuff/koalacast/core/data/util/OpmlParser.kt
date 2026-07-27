package net.koalastuff.koalacast.core.data.util

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class OpmlFeed(
    val feedUrl: String,
    val title: String,
)

object OpmlParser {

    /**
     * Parses an OPML XML string into a list of [OpmlFeed] objects.
     * Handles nested outline structures, UTF-8 BOM, and common attribute variations.
     */
    fun parse(xml: String): List<OpmlFeed> {
        val sanitizedXml = stripBom(xml).trim()
        if (sanitizedXml.isEmpty()) return emptyList()

        val feeds = mutableListOf<OpmlFeed>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isIgnoringComments = true
            val builder = factory.newDocumentBuilder()
            val inputSource = InputSource(StringReader(sanitizedXml))
            val doc = builder.parse(inputSource)

            val outlines = doc.getElementsByTagName("outline")
            for (i in 0 until outlines.length) {
                val node = outlines.item(i)
                if (node is Element) {
                    val feedUrl = getAttr(node, "xmlUrl")
                        ?: getAttr(node, "xmlurl")
                        ?: getAttr(node, "XMLURL")
                        ?: getAttr(node, "url")
                        ?: getAttr(node, "URL")

                    if (!feedUrl.isNullOrBlank()) {
                        val rawTitle = getAttr(node, "title")
                            ?: getAttr(node, "text")
                            ?: feedUrl
                        val cleanTitle = if (rawTitle.isBlank()) feedUrl else rawTitle
                        feeds.add(OpmlFeed(feedUrl = feedUrl.trim(), title = cleanTitle.trim()))
                    }
                }
            }
        } catch (_: Exception) {
            // Ignored, returns parsed feeds
        }
        return feeds
    }

    private fun getAttr(element: Element, attrName: String): String? {
        val attributes = element.attributes ?: return null
        for (i in 0 until attributes.length) {
            val item = attributes.item(i)
            if (item.nodeName.equals(attrName, ignoreCase = true)) {
                val value = item.nodeValue
                if (!value.isNullOrBlank()) return value
            }
        }
        return null
    }

    private fun stripBom(xml: String): String {
        return if (xml.startsWith("\uFEFF")) xml.substring(1) else xml
    }
}

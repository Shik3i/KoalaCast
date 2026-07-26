package net.koalastuff.koalacast.core.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * Show notes are attacker-controlled HTML from a third-party feed, so they are
 * treated as hostile input on the way in *and* on the way out:
 *
 * 1. [HtmlSanitizer] drops script/style/iframe/object/embed blocks along with their
 *    content, and strips every `on*` event attribute.
 * 2. Rendering goes through Compose's HTML-to-AnnotatedString conversion, which maps
 *    a small tag set to spans and ignores everything else — no WebView, no JS engine,
 *    no remote image fetch.
 * 3. Links are opened only when they are `http`/`https`; a `javascript:` or `intent:`
 *    href is ignored rather than handed to the system.
 *
 * This is the Android counterpart to the web client's DOMPurify pass.
 */
@Composable
fun ShowNotes(
    html: String,
    modifier: Modifier = Modifier,
) {
    val colors = KoalaTheme.colors
    val uriHandler = LocalUriHandler.current

    val annotated: AnnotatedString = remember(html, colors.accentInk) {
        AnnotatedString.fromHtml(
            htmlString = HtmlSanitizer.sanitize(html),
            linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = colors.accentInk,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
            linkInteractionListener = { annotation ->
                val url = (annotation as? LinkAnnotation.Url)?.url.orEmpty()
                if (HtmlSanitizer.isSafeLink(url)) {
                    uriHandler.openUri(url)
                }
            },
        )
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = KoalaTheme.type.body,
        color = colors.ink3,
    )
}

object HtmlSanitizer {

    /** Elements whose *content* is dropped, not just their tags. */
    private val DANGEROUS_BLOCKS =
        Regex("(?is)<(script|style|iframe|object|embed|form|svg)\\b[^>]*>.*?</\\1\\s*>")

    /** The same elements, self-closing or unterminated. */
    private val DANGEROUS_TAGS =
        Regex("(?is)</?(script|style|iframe|object|embed|form|svg)\\b[^>]*>")

    /** `onclick=…`, `onerror=…` — inline handlers in any quoting style. */
    private val EVENT_ATTRIBUTES =
        Regex("(?is)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")

    /** `href="javascript:…"` and friends, reduced to an inert anchor. */
    private val UNSAFE_HREF =
        Regex("(?is)\\s(href|src)\\s*=\\s*(\"|')?\\s*(javascript|data|vbscript|intent|file):[^\"'>\\s]*(\"|')?")

    fun sanitize(html: String): String =
        html
            .replace(DANGEROUS_BLOCKS, "")
            .replace(DANGEROUS_TAGS, "")
            .replace(EVENT_ATTRIBUTES, "")
            .replace(UNSAFE_HREF, "")
            .trim()

    fun isSafeLink(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("mailto:", ignoreCase = true)
}

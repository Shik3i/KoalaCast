package net.koalastuff.koalacast.core.ui.language

/**
 * Content languages Discover and Search can be filtered by — mirrors
 * `apps/web/src/lib/data/languages.ts`.
 *
 * A language is not a storefront region: the German storefront is full of English
 * shows, so the server filters on the feed's own RSS `<language>` instead. [region]
 * is only the storefront a chart is *pulled* from.
 *
 * Names are endonyms and are deliberately not translated — a language is always
 * listed in its own language.
 */
data class ContentLanguage(
    val code: String,
    val name: String,
    val region: String,
)

val CONTENT_LANGUAGES: List<ContentLanguage> = listOf(
    ContentLanguage("de", "Deutsch", "de"),
    ContentLanguage("en", "English", "us"),
    ContentLanguage("fr", "Français", "fr"),
    ContentLanguage("es", "Español", "es"),
    ContentLanguage("it", "Italiano", "it"),
    ContentLanguage("pt", "Português", "pt"),
    ContentLanguage("nl", "Nederlands", "nl"),
)

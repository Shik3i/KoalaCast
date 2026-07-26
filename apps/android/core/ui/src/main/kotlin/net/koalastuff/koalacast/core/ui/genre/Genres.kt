package net.koalastuff.koalacast.core.ui.genre

import androidx.annotation.StringRes
import net.koalastuff.koalacast.core.ui.R

/**
 * Mirrors `apps/web/src/lib/genres.ts` — Apple Podcasts' top-level categories, which
 * Podcast Index also understands.
 *
 * [wireName] is a protocol value sent as `?category=` and matched against a feed's own
 * categories. It must stay English and must never be translated; [labelRes] is the
 * display side. Keep this list in step with the web catalogue.
 */
data class Genre(
    val id: String,
    val wireName: String,
    @StringRes val labelRes: Int,
)

val GENRES: List<Genre> = listOf(
    Genre("arts", "Arts", R.string.genre_arts),
    Genre("business", "Business", R.string.genre_business),
    Genre("comedy", "Comedy", R.string.genre_comedy),
    Genre("education", "Education", R.string.genre_education),
    Genre("fiction", "Fiction", R.string.genre_fiction),
    Genre("government", "Government", R.string.genre_government),
    Genre("healthFitness", "Health & Fitness", R.string.genre_health_fitness),
    Genre("history", "History", R.string.genre_history),
    Genre("kidsFamily", "Kids & Family", R.string.genre_kids_family),
    Genre("leisure", "Leisure", R.string.genre_leisure),
    Genre("music", "Music", R.string.genre_music),
    Genre("news", "News", R.string.genre_news),
    Genre("religionSpirituality", "Religion & Spirituality", R.string.genre_religion_spirituality),
    Genre("science", "Science", R.string.genre_science),
    Genre("societyCulture", "Society & Culture", R.string.genre_society_culture),
    Genre("sports", "Sports", R.string.genre_sports),
    Genre("technology", "Technology", R.string.genre_technology),
    Genre("tvFilm", "TV & Film", R.string.genre_tv_film),
    Genre("trueCrime", "True Crime", R.string.genre_true_crime),
)

private val BY_WIRE_NAME = GENRES.associateBy { it.wireName.lowercase() }

/**
 * The label resource for a wire value, or `null` when the feed supplied a category of
 * its own — a publisher's category is not ours to rewrite, so it is shown verbatim.
 */
@StringRes
fun genreLabelRes(wireName: String): Int? = BY_WIRE_NAME[wireName.lowercase()]?.labelRes

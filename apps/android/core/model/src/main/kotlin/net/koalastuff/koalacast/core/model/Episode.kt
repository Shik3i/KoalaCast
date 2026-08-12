package net.koalastuff.koalacast.core.model

/**
 * Durations and positions are milliseconds everywhere in KoalaCast — server, web and
 * here. Never introduce a seconds-based field.
 */
data class Episode(
    val id: String,
    val podcastId: String,
    val guid: String,
    val title: String,
    val description: String,
    val contentEncoded: String,
    val pubDateMs: Long,
    val hasPubDate: Boolean,
    /** Publisher's Podcasting 2.0 chapters JSON; blank when the feed has none. */
    val chaptersUrl: String = "",
    val durationMs: Long,
    val enclosureUrl: String,
    val enclosureType: String,
    val enclosureLengthBytes: Long,
    val artworkUrl: String,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val episodeType: String,
    val explicit: Boolean?,
    val link: String,
    val transcripts: List<Transcript>,
) {
    /** Show notes, richest field first. Both are attacker-controlled HTML. */
    val notesHtml: String get() = contentEncoded.ifBlank { description }
}

data class Transcript(
    val url: String,
    val type: String,
)

/** One entry of a Podcasting 2.0 chapters file. */
data class Chapter(
    val startMs: Long,
    val title: String,
    val imageUrl: String,
    val linkUrl: String,
)

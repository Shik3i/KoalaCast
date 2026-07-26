package net.koalastuff.koalacast.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TranscriptDto(
    val url: String = "",
    val type: String = "",
)

@Serializable
data class EpisodeDto(
    val id: String = "",
    @SerialName("podcast_id") val podcastId: String = "",
    val guid: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("content_encoded") val contentEncoded: String = "",
    @SerialName("pub_date") val pubDate: Long = 0,
    @SerialName("has_pub_date") val hasPubDate: Boolean = false,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("enclosure_url") val enclosureUrl: String = "",
    @SerialName("enclosure_type") val enclosureType: String = "",
    @SerialName("enclosure_length") val enclosureLength: Long = 0,
    @SerialName("artwork_url") val artworkUrl: String = "",
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_type") val episodeType: String = "",
    val explicit: Boolean = false,
    val link: String = "",
    val transcripts: List<TranscriptDto> = emptyList(),
)

@Serializable
data class EpisodesResponse(
    val episodes: List<EpisodeDto> = emptyList(),
    val count: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class HealthResponse(
    val status: String = "",
    val version: String = "",
)

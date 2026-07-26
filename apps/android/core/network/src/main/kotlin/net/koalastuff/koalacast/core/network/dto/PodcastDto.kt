package net.koalastuff.koalacast.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/v1/podcasts/discover` and `…/search` result item. */
@Serializable
data class PodcastSummaryDto(
    val id: String = "",
    val title: String = "",
    val author: String = "",
    @SerialName("feed_url") val feedUrl: String = "",
    @SerialName("artwork_url") val artworkUrl: String = "",
    val category: String = "",
    val categories: List<String> = emptyList(),
    val description: String = "",
    val language: String = "",
)

@Serializable
data class DiscoverResponse(
    val status: String = "",
    val results: List<PodcastSummaryDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class SearchResponse(
    @SerialName("search_available") val searchAvailable: Boolean = false,
    val provider: String = "",
    val results: List<PodcastSummaryDto> = emptyList(),
)

/** `GET /api/v1/podcasts/{id}` and the body of `POST /api/v1/podcasts/feed`. */
@Serializable
data class PodcastDto(
    val id: String = "",
    @SerialName("feed_url") val feedUrl: String = "",
    val title: String = "",
    val description: String = "",
    val author: String = "",
    @SerialName("artwork_url") val artworkUrl: String = "",
    val link: String = "",
    val language: String = "",
    val explicit: Boolean = false,
    val copyright: String = "",
    @SerialName("last_successful_fetch_at") val lastSuccessfulFetchAt: Long = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
)

@Serializable
data class AddFeedRequest(
    @SerialName("feed_url") val feedUrl: String,
)

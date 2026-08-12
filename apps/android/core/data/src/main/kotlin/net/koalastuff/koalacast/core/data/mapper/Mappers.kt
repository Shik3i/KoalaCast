package net.koalastuff.koalacast.core.data.mapper

import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.model.PodcastSummary
import net.koalastuff.koalacast.core.model.Transcript
import net.koalastuff.koalacast.core.network.dto.EpisodeDto
import net.koalastuff.koalacast.core.network.dto.PodcastDto
import net.koalastuff.koalacast.core.network.dto.PodcastSummaryDto
import net.koalastuff.koalacast.core.network.dto.TranscriptDto

fun PodcastSummaryDto.toModel() = PodcastSummary(
    id = id,
    title = title,
    author = author,
    feedUrl = feedUrl,
    artworkUrl = artworkUrl,
    category = category,
    categories = categories,
    description = description,
    language = language,
    explicit = explicit,
)

fun PodcastDto.toModel() = Podcast(
    id = id,
    feedUrl = feedUrl,
    title = title,
    description = description,
    author = author,
    artworkUrl = artworkUrl,
    link = link,
    language = language,
    explicit = explicit,
    copyright = copyright,
    lastSuccessfulFetchAtMs = lastSuccessfulFetchAt,
    episodeCount = episodeCount,
)

fun TranscriptDto.toModel() = Transcript(url = url, type = type)

fun EpisodeDto.toModel() = Episode(
    id = id,
    podcastId = podcastId,
    guid = guid,
    title = title,
    description = description,
    contentEncoded = contentEncoded,
    // The API reports pub_date in whole Unix *seconds* (the web client documents it
    // as such and multiplies too). Without this, every episode dates to Jan 1970.
    pubDateMs = if (hasPubDate) pubDate * 1000L else 0L,
    hasPubDate = hasPubDate,
    chaptersUrl = chaptersUrl,
    durationMs = durationMs,
    enclosureUrl = enclosureUrl,
    enclosureType = enclosureType,
    enclosureLengthBytes = enclosureLength,
    artworkUrl = artworkUrl,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    episodeType = episodeType,
    explicit = explicit,
    link = link,
    transcripts = transcripts.map { it.toModel() },
)

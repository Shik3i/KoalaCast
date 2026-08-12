package net.koalastuff.koalacast.core.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import net.koalastuff.koalacast.core.data.di.IoDispatcher
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.mapper.toModel
import net.koalastuff.koalacast.core.model.Chapter
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.model.PodcastSummary
import net.koalastuff.koalacast.core.model.map
import net.koalastuff.koalacast.core.model.isAllowedByExplicitPreference
import net.koalastuff.koalacast.core.network.KoalaCastApi
import net.koalastuff.koalacast.core.network.apiCall
import net.koalastuff.koalacast.core.network.dto.AddFeedRequest
import net.koalastuff.koalacast.core.network.dto.DiscoverResponse
import net.koalastuff.koalacast.core.network.dto.EpisodeDto
import net.koalastuff.koalacast.core.network.dto.EpisodesResponse
import net.koalastuff.koalacast.core.network.dto.PodcastDto
import net.koalastuff.koalacast.core.network.dto.SearchResponse
import net.koalastuff.koalacast.core.network.dto.ChaptersResponse
import net.koalastuff.koalacast.core.network.dto.TranscriptContentDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only access to the server's podcast catalogue. Nothing here writes to the
 * device yet — subscriptions, queue and progress land with the Room work (P3).
 */
@Singleton
class PodcastRepository @Inject constructor(
    private val api: KoalaCastApi,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val cache: ContentCache? = null,
    private val preferences: PreferencesRepository? = null,
) {
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    private val refreshedAt = ConcurrentHashMap<String, Long>()

    suspend fun discover(
        category: String? = null,
        region: String? = null,
        languages: Set<String> = emptySet(),
        limit: Int? = null,
    ): DataResult<List<PodcastSummary>> = withContext(dispatcher) {
        val includeExplicit = includeExplicit()
        val result = apiCall {
            api.discover(
                category = category?.takeIf { it.isNotBlank() },
                region = region?.takeIf { it.isNotBlank() },
                languages = languages.joinToString(",").takeIf { it.isNotBlank() },
                limit = limit,
                includeExplicit = includeExplicit,
            )
        }
        if (result is DataResult.Success) {
            cache?.put(
                discoverKey(category, region, languages, limit, includeExplicit),
                result.data,
                DiscoverResponse.serializer(),
            )
        }
        result.map { response ->
            response.results.map { it.toModel() }
                .filter { it.explicit.isAllowedByExplicitPreference(includeExplicit) }
        }
    }

    suspend fun cachedDiscover(
        category: String? = null,
        region: String? = null,
        languages: Set<String> = emptySet(),
        limit: Int? = null,
    ): CachedContent<List<PodcastSummary>>? = withContext(dispatcher) {
        val includeExplicit = includeExplicit()
        cache?.get(
            discoverKey(category, region, languages, limit, includeExplicit),
            DiscoverResponse.serializer(),
        )?.mapValue {
            it.results.map { summary -> summary.toModel() }
                .filter { summary -> summary.explicit.isAllowedByExplicitPreference(includeExplicit) }
        }
    }

    suspend fun search(
        query: String,
        languages: Set<String> = emptySet(),
        category: String? = null,
    ): DataResult<List<PodcastSummary>> = withContext(dispatcher) {
        val includeExplicit = includeExplicit()
        val result = apiCall {
            api.search(
                query = query,
                languages = languages.joinToString(",").takeIf { it.isNotBlank() },
                category = category?.takeIf { it.isNotBlank() },
                includeExplicit = includeExplicit,
            )
        }
        if (result is DataResult.Success) {
            cache?.put(
                searchKey(query, languages, category, includeExplicit),
                result.data,
                SearchResponse.serializer(),
            )
        }
        result.map { response ->
            response.results.map { it.toModel() }
                .filter { it.explicit.isAllowedByExplicitPreference(includeExplicit) }
        }
    }

    suspend fun cachedSearch(
        query: String,
        languages: Set<String> = emptySet(),
        category: String? = null,
    ): CachedContent<List<PodcastSummary>>? = withContext(dispatcher) {
        val includeExplicit = includeExplicit()
        cache?.get(
            searchKey(query, languages, category, includeExplicit),
            SearchResponse.serializer(),
        )?.mapValue {
            it.results.map { summary -> summary.toModel() }
                .filter { summary -> summary.explicit.isAllowedByExplicitPreference(includeExplicit) }
        }
    }

    /**
     * Discovery hands back provider ids (iTunes / Podcast Index), not KoalaCast ids.
     * Posting the feed URL resolves — and, first time round, ingests — the show, which
     * is also how "add by RSS URL" works.
     */
    suspend fun resolveFeed(feedUrl: String): DataResult<Podcast> = withContext(dispatcher) {
        val result = apiCall { api.addFeed(AddFeedRequest(feedUrl)) }
        if (result is DataResult.Success) {
            cache?.put(feedKey(feedUrl), result.data, PodcastDto.serializer())
            cache?.put(podcastKey(result.data.id), result.data, PodcastDto.serializer())
        }
        result.map { it.toModel() }
    }

    suspend fun cachedResolvedFeed(feedUrl: String): CachedContent<Podcast>? =
        withContext(dispatcher) {
            cache?.get(feedKey(feedUrl), PodcastDto.serializer())?.mapValue { it.toModel() }
        }

    suspend fun podcast(id: String): DataResult<Podcast> = withContext(dispatcher) {
        val result = apiCall { api.podcast(id) }
        if (result is DataResult.Success) {
            cache?.put(podcastKey(id), result.data, PodcastDto.serializer())
        }
        result.map { it.toModel() }
    }

    suspend fun cachedPodcast(id: String): CachedContent<Podcast>? = withContext(dispatcher) {
        cache?.get(podcastKey(id), PodcastDto.serializer())?.mapValue { it.toModel() }
    }

    suspend fun episodes(
        podcastId: String,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): DataResult<List<Episode>> = withContext(dispatcher) {
        val includeExplicit = includeExplicit()
        val result = apiCall { api.episodes(podcastId, limit, offset) }
        if (result is DataResult.Success) {
            cache?.put(episodesKey(podcastId, limit, offset), result.data, EpisodesResponse.serializer())
            result.data.episodes.forEach { episode ->
                cache?.put(episodeKey(episode.id), episode, EpisodeDto.serializer())
            }
        }
        result.map { response ->
            response.episodes.map { it.toModel() }
                .filter { it.explicit.isAllowedByExplicitPreference(includeExplicit) }
        }
    }

    suspend fun cachedEpisodes(
        podcastId: String,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): CachedContent<List<Episode>>? = withContext(dispatcher) {
        val includeExplicit = includeExplicit()
        cache?.get(
            episodesKey(podcastId, limit, offset),
            EpisodesResponse.serializer(),
        )?.mapValue {
            it.episodes.map { episode -> episode.toModel() }
                .filter { episode -> episode.explicit.isAllowedByExplicitPreference(includeExplicit) }
        }
    }

    /**
     * Inbox refresh: ask only for the inclusive publication boundary and merge
     * by stable episode id. Existing rows stay visible on every failure.
     */
    suspend fun refreshEpisodesIncrementally(
        podcastId: String,
        limit: Int = PAGE_SIZE,
    ): DataResult<List<Episode>> = withContext(dispatcher) {
        val includeExplicit = includeExplicit()
        val key = episodesKey(podcastId, limit, 0)
        refreshLocks.getOrPut(key) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            if (now - (refreshedAt[key] ?: 0L) < REVALIDATION_DEDUPE_MS) {
                val existing = cache?.get(key, EpisodesResponse.serializer())
                if (existing != null) {
                    return@withLock DataResult.Success(
                        existing.value.episodes.map { it.toModel() }
                            .filter { it.explicit.isAllowedByExplicitPreference(includeExplicit) },
                    )
                }
            }
        val cached = cache?.get(key, EpisodesResponse.serializer())
        val since = cached?.value?.episodes
            ?.maxOfOrNull { it.pubDate }
            ?.takeIf { it > 0 }
        val result = apiCall { api.episodes(podcastId, limit, 0, since) }
        when (result) {
            is DataResult.Failure -> result
            is DataResult.Success -> {
                val merged = (cached?.value?.episodes.orEmpty() + result.data.episodes)
                    .associateBy { it.id }
                    .values
                    .sortedByDescending { it.pubDate }
                    .take(limit)
                val envelope = EpisodesResponse(
                    episodes = merged,
                    count = merged.size,
                    limit = limit,
                    offset = 0,
                )
                cache?.put(key, envelope, EpisodesResponse.serializer())
                merged.forEach { episode ->
                    cache?.put(episodeKey(episode.id), episode, EpisodeDto.serializer())
                }
                refreshedAt[key] = now
                DataResult.Success(
                    merged.map { it.toModel() }
                        .filter { it.explicit.isAllowedByExplicitPreference(includeExplicit) },
                )
            }
        }
        }
    }

    suspend fun episode(id: String): DataResult<Episode> = withContext(dispatcher) {
        val result = apiCall { api.episode(id) }
        if (result is DataResult.Success) {
            cache?.put(episodeKey(id), result.data, EpisodeDto.serializer())
        }
        result.map { it.toModel() }
    }

    suspend fun cachedEpisode(id: String): CachedContent<Episode>? = withContext(dispatcher) {
        cache?.get(episodeKey(id), EpisodeDto.serializer())?.mapValue { it.toModel() }
    }

    fun isFresh(content: CachedContent<*>, ttlMs: Long): Boolean =
        content.isFresh(cache?.now() ?: System.currentTimeMillis(), ttlMs)

    private suspend fun includeExplicit(): Boolean =
        preferences?.preferences?.first()?.allowExplicitContent ?: false

    suspend fun chapters(chaptersUrl: String): DataResult<List<Chapter>> =
        withContext(dispatcher) {
            val result = apiCall { api.chapters(chaptersUrl) }
            if (result is DataResult.Success) {
                cache?.put(chaptersKey(chaptersUrl), result.data, ChaptersResponse.serializer())
            }
            result.map(::mapChapters)
        }

    suspend fun cachedChapters(chaptersUrl: String): CachedContent<List<Chapter>>? =
        withContext(dispatcher) {
            cache?.get(chaptersKey(chaptersUrl), ChaptersResponse.serializer())
                ?.mapValue(::mapChapters)
        }

    suspend fun transcript(id: String, index: Int = 0): DataResult<Pair<String, String>> =
        withContext(dispatcher) {
            val result = apiCall { api.transcript(id, index) }
            if (result is DataResult.Success) {
                cache?.put(transcriptKey(id, index), result.data, TranscriptContentDto.serializer())
            }
            result.map { it.type to it.content }
        }

    suspend fun cachedTranscript(
        id: String,
        index: Int = 0,
    ): CachedContent<Pair<String, String>>? = withContext(dispatcher) {
        cache?.get(transcriptKey(id, index), TranscriptContentDto.serializer())
            ?.mapValue { it.type to it.content }
    }

    companion object {
        const val PAGE_SIZE = 50
        private const val REVALIDATION_DEDUPE_MS = 30_000L

        private fun normalizedLanguages(languages: Set<String>) =
            languages.filter { it.isNotBlank() }.sorted().joinToString(",")

        private fun discoverKey(
            category: String?,
            region: String?,
            languages: Set<String>,
            limit: Int?,
            includeExplicit: Boolean,
        ) = "discover:${category.orEmpty()}:${region.orEmpty()}:${normalizedLanguages(languages)}:${limit ?: 0}:explicit=$includeExplicit"

        private fun searchKey(
            query: String,
            languages: Set<String>,
            category: String?,
            includeExplicit: Boolean,
        ) = "search:${query.trim().lowercase()}:${normalizedLanguages(languages)}:${category.orEmpty()}:explicit=$includeExplicit"

        private fun feedKey(feedUrl: String) = "podcast:feed:$feedUrl"
        private fun podcastKey(id: String) = "podcast:id:$id"
        private fun episodesKey(id: String, limit: Int, offset: Int) = "episodes:$id:$limit:$offset"
        private fun episodeKey(id: String) = "episode:$id"
        private fun chaptersKey(url: String) = "chapters:$url"
        private fun transcriptKey(id: String, index: Int) = "transcript:$id:$index"
    }
}

private inline fun <T, R> CachedContent<T>.mapValue(transform: (T) -> R): CachedContent<R> =
    CachedContent(transform(value), storedAt)

private fun mapChapters(response: ChaptersResponse): List<Chapter> =
    response.chapters
        .map {
            Chapter(
                // Podcasting 2.0 gives startTime in seconds, often fractional.
                startMs = (it.startTime * 1000).toLong().coerceAtLeast(0L),
                title = it.title,
                imageUrl = it.img,
                linkUrl = it.url,
            )
        }
        .filter { it.title.isNotBlank() }
        .sortedBy { it.startMs }

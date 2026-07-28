package net.koalastuff.koalacast.core.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.koalastuff.koalacast.core.data.di.IoDispatcher
import net.koalastuff.koalacast.core.data.mapper.toModel
import net.koalastuff.koalacast.core.model.Chapter
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.Episode
import net.koalastuff.koalacast.core.model.Podcast
import net.koalastuff.koalacast.core.model.PodcastSummary
import net.koalastuff.koalacast.core.model.map
import net.koalastuff.koalacast.core.network.KoalaCastApi
import net.koalastuff.koalacast.core.network.apiCall
import net.koalastuff.koalacast.core.network.dto.AddFeedRequest
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
) {

    suspend fun discover(
        category: String? = null,
        region: String? = null,
        languages: Set<String> = emptySet(),
        limit: Int? = null,
    ): DataResult<List<PodcastSummary>> = withContext(dispatcher) {
        apiCall {
            api.discover(
                category = category?.takeIf { it.isNotBlank() },
                region = region?.takeIf { it.isNotBlank() },
                languages = languages.joinToString(",").takeIf { it.isNotBlank() },
                limit = limit,
            )
        }.map { response -> response.results.map { it.toModel() } }
    }

    suspend fun search(
        query: String,
        languages: Set<String> = emptySet(),
        category: String? = null,
    ): DataResult<List<PodcastSummary>> = withContext(dispatcher) {
        apiCall {
            api.search(
                query = query,
                languages = languages.joinToString(",").takeIf { it.isNotBlank() },
                category = category?.takeIf { it.isNotBlank() },
            )
        }.map { response -> response.results.map { it.toModel() } }
    }

    /**
     * Discovery hands back provider ids (iTunes / Podcast Index), not KoalaCast ids.
     * Posting the feed URL resolves — and, first time round, ingests — the show, which
     * is also how "add by RSS URL" works.
     */
    suspend fun resolveFeed(feedUrl: String): DataResult<Podcast> = withContext(dispatcher) {
        apiCall { api.addFeed(AddFeedRequest(feedUrl)) }.map { it.toModel() }
    }

    suspend fun podcast(id: String): DataResult<Podcast> = withContext(dispatcher) {
        apiCall { api.podcast(id) }.map { it.toModel() }
    }

    suspend fun episodes(
        podcastId: String,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): DataResult<List<Episode>> = withContext(dispatcher) {
        apiCall { api.episodes(podcastId, limit, offset) }
            .map { response -> response.episodes.map { it.toModel() } }
    }

    suspend fun episode(id: String): DataResult<Episode> = withContext(dispatcher) {
        apiCall { api.episode(id) }.map { it.toModel() }
    }

    suspend fun chapters(chaptersUrl: String): DataResult<List<Chapter>> =
        withContext(dispatcher) {
            apiCall { api.chapters(chaptersUrl) }.map { response ->
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
            }
        }

    suspend fun transcript(id: String, index: Int = 0): DataResult<Pair<String, String>> =
        withContext(dispatcher) {
            apiCall { api.transcript(id, index) }.map { it.type to it.content }
        }

    companion object {
        const val PAGE_SIZE = 50
    }
}

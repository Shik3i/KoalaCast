package net.koalastuff.koalacast.core.data.repository

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.network.KoalaCastApi
import net.koalastuff.koalacast.core.data.db.ContentCacheDao
import net.koalastuff.koalacast.core.data.db.ContentCacheEntity
import net.koalastuff.koalacast.core.data.util.Clock
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class PodcastRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: PodcastRepository
    private lateinit var cacheDao: InMemoryContentCacheDao

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KoalaCastApi::class.java)

        // A real dispatcher: the repository's job here is the network round trip,
        // and runTest awaits the suspend call either way.
        cacheDao = InMemoryContentCacheDao()
        val cache = ContentCache(
            cacheDao,
            json,
            object : Clock {
                override fun nowMs() = 1_800_000L
            },
        )
        repository = PodcastRepository(api, Dispatchers.IO, cache)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `discover maps the server's chart payload`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """
                {"status":"ok","total":1,"results":[
                  {"id":"123","title":"Northbound Signal","author":"Marlow & Vey",
                   "feed_url":"https://example.org/feed.xml","artwork_url":"https://cdn.example/a.jpg",
                   "category":"Science","categories":["Science","Technology"],
                   "description":"Slow, structural curiosity.","language":"en"}
                ]}
                """.trimIndent(),
            ).build(),
        )

        val result = repository.discover(
            region = "de",
            languages = setOf("en", "de"),
            limit = 60,
        )

        assertTrue(result is DataResult.Success)
        val show = (result as DataResult.Success).data.single()
        assertEquals("Northbound Signal", show.title)
        assertEquals("https://example.org/feed.xml", show.feedUrl)
        assertEquals(listOf("Science", "Technology"), show.categories)

        val request = server.takeRequest()
        assertEquals("/api/v1/podcasts/discover", request.url.encodedPath)
        // Language filtering is a comma list, and the blank category is omitted.
        assertEquals(setOf("en", "de"), request.url.queryParameter("languages")!!.split(",").toSet())
        assertEquals("de", request.url.queryParameter("region"))
        assertEquals(null, request.url.queryParameter("category"))
    }

    @Test
    fun `episodes keep millisecond precision and unwrap the envelope`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """
                {"count":1,"limit":50,"offset":0,"episodes":[
                  {"id":"e1","podcast_id":"p1","title":"Quiet rooms, loud data",
                   "pub_date":1753401600,"has_pub_date":true,"duration_ms":2947000,
                   "enclosure_url":"https://cdn.example/ep.mp3","episode_number":214,
                   "transcripts":[{"url":"https://example.org/t.vtt","type":"text/vtt"}]}
                ]}
                """.trimIndent(),
            ).build(),
        )

        val result = repository.episodes("p1")

        assertTrue(result is DataResult.Success)
        val episode = (result as DataResult.Success).data.single()
        assertEquals(2_947_000L, episode.durationMs)
        assertEquals(1_753_401_600_000L, episode.pubDateMs)
        assertEquals(214, episode.episodeNumber)
        assertEquals("text/vtt", episode.transcripts.single().type)
    }

    @Test
    fun `incremental episode refresh keeps cached rows and requests only the boundary`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """
                {"count":2,"limit":15,"offset":0,"episodes":[
                  {"id":"e2","podcast_id":"p1","title":"Second","pub_date":200,"has_pub_date":true,
                   "enclosure_url":"https://cdn.example/e2.mp3"},
                  {"id":"e1","podcast_id":"p1","title":"First","pub_date":100,"has_pub_date":true,
                   "enclosure_url":"https://cdn.example/e1.mp3"}
                ]}
                """.trimIndent(),
            ).build(),
        )
        repository.episodes("p1", limit = 15)

        server.enqueue(
            MockResponse.Builder().code(200).body(
                """
                {"count":2,"limit":15,"offset":0,"since":200,"episodes":[
                  {"id":"e3","podcast_id":"p1","title":"Third","pub_date":300,"has_pub_date":true,
                   "enclosure_url":"https://cdn.example/e3.mp3"},
                  {"id":"e2","podcast_id":"p1","title":"Second updated","pub_date":200,"has_pub_date":true,
                   "enclosure_url":"https://cdn.example/e2.mp3"}
                ]}
                """.trimIndent(),
            ).build(),
        )

        val result = repository.refreshEpisodesIncrementally("p1", limit = 15)

        assertEquals(
            listOf("e3", "e2", "e1"),
            (result as DataResult.Success).data.map { it.id },
        )
        server.takeRequest()
        val incremental = server.takeRequest()
        assertEquals("200", incremental.url.queryParameter("since"))
    }

    @Test
    fun `a rate-limited discovery surfaces as an Http failure, not an exception`() = runTest {
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":"slow down"}""").build())

        val result = repository.discover()

        assertTrue(result is DataResult.Failure)
        val error = (result as DataResult.Failure).error
        assertTrue(error is DataError.Http)
        assertEquals(429, (error as DataError.Http).code)
    }

    @Test
    fun `an unreachable server surfaces as a Network failure`() = runTest {
        server.close()

        val result = repository.discover()

        assertTrue(result is DataResult.Failure)
        assertTrue((result as DataResult.Failure).error is DataError.Network)
    }

    @Test
    fun `resolving a feed posts the URL the caller supplied`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"id":"p1","feed_url":"https://example.org/feed.xml","title":"Paper Maps","episode_count":41}""")
                .build(),
        )

        val result = repository.resolveFeed("https://example.org/feed.xml")

        assertTrue(result is DataResult.Success)
        assertEquals("p1", (result as DataResult.Success).data.id)

        val request = server.takeRequest()
        assertEquals("/api/v1/podcasts/feed", request.url.encodedPath)
        assertTrue(request.body!!.utf8().contains("https://example.org/feed.xml"))
    }
}

private class InMemoryContentCacheDao : ContentCacheDao {
    private val rows = mutableMapOf<String, ContentCacheEntity>()

    override suspend fun get(key: String): ContentCacheEntity? = rows[key]

    override suspend fun upsert(entry: ContentCacheEntity) {
        rows[entry.cacheKey] = entry
    }

}

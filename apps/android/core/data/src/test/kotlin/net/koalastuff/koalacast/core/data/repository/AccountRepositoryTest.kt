package net.koalastuff.koalacast.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.network.KoalaCastApi
import net.koalastuff.koalacast.core.network.dto.AddFeedRequest
import net.koalastuff.koalacast.core.network.dto.PodcastDto
import retrofit2.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class AccountRepositoryTest {
    private lateinit var database: KoalaCastDatabase
    private lateinit var repository: AccountRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("secure_account", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, KoalaCastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val importedPodcasts = listOf(
            PodcastDto(
                id = "first-id",
                title = "First show",
                feedUrl = "https://example.com/first.xml",
                artworkUrl = "https://example.com/first.jpg",
            ),
            PodcastDto(
                id = "second-id",
                title = "Second show",
                feedUrl = "https://example.com/second.xml",
                artworkUrl = "https://example.com/second.jpg",
            ),
        )
        val api = Proxy.newProxyInstance(
            KoalaCastApi::class.java.classLoader,
            arrayOf(KoalaCastApi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addFeed" -> Response.success(
                    importedPodcasts.first {
                        it.feedUrl == (args.first() as AddFeedRequest).feedUrl
                    },
                )
                else -> error("unexpected API call: ${method.name}")
            }
        } as KoalaCastApi
        val library = LibraryRepository(
            subscriptions = database.subscriptionDao(),
            favorites = database.favoriteDao(),
            tombstones = database.tombstoneDao(),
            podcastSettings = database.podcastSettingsDao(),
            clock = object : Clock {
                override fun nowMs(): Long = 1_700_000_000_000L
            },
        )
        repository = AccountRepository(
            api = api,
            store = SecureAccountStore(context),
            library = library,
            podcasts = PodcastRepository(api, Dispatchers.IO),
            dispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `OPML import stores canonical subscriptions with artwork`() = runTest {
        val xml = """
            <opml version="2.0">
              <body>
                <outline text="First show" xmlUrl="https://example.com/first.xml" />
                <outline text="Second show" xmlUrl="https://example.com/second.xml" />
              </body>
            </opml>
        """.trimIndent()

        val first = repository.importOpml(xml)
        val second = repository.importOpml(xml)

        assertTrue(first is DataResult.Success)
        assertEquals(2, (first as DataResult.Success).data.imported)
        assertTrue(second is DataResult.Success)
        assertEquals(0, (second as DataResult.Success).data.imported)
        assertEquals(2, second.data.skipped)
        assertEquals(
            setOf("first-id", "second-id"),
            database.subscriptionDao().getAll().map { it.podcastId }.toSet(),
        )
        assertTrue(database.subscriptionDao().getAll().all { it.artworkUrl.isNotBlank() })
    }

    @Test
    fun `invalid OPML fails immediately instead of starting a server request`() = runTest {
        val result = repository.importOpml("<opml><body /></opml>")

        assertTrue(result is DataResult.Failure)
        assertTrue(database.subscriptionDao().getAll().isEmpty())
    }
}

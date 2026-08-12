package net.koalastuff.koalacast.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.db.EpisodeDownloadEntity
import net.koalastuff.koalacast.core.data.db.ListeningSessionEntity
import net.koalastuff.koalacast.core.data.db.SubscriptionEntity
import net.koalastuff.koalacast.core.data.db.TombstoneEntity
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.network.KoalaCastApi
import net.koalastuff.koalacast.core.network.dto.SyncChangesetDto
import net.koalastuff.koalacast.core.model.Account
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.network.dto.SyncPushRequest
import net.koalastuff.koalacast.core.network.dto.SyncPushResponse
import net.koalastuff.koalacast.core.network.dto.SyncPullResponse
import net.koalastuff.koalacast.core.network.dto.SyncSnapshotResponse
import net.koalastuff.koalacast.core.network.dto.SyncOperationDto
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {
    private lateinit var database: KoalaCastDatabase
    private lateinit var repository: SyncRepository
    private lateinit var store: SecureAccountStore
    private lateinit var preferences: PreferencesRepository
    private var apiHandler: (Method, Array<out Any?>?) -> Any? = { method, _ ->
        error("unexpected API call: ${method.name}")
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("secure_account", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, KoalaCastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val api = Proxy.newProxyInstance(
            KoalaCastApi::class.java.classLoader,
            arrayOf(KoalaCastApi::class.java),
        ) { _, method, args -> apiHandler(method, args) } as KoalaCastApi
        store = SecureAccountStore(context)
        val preferenceValues = MutableStateFlow<Preferences>(emptyPreferences())
        val preferenceDataStore = object : DataStore<Preferences> {
            override val data = preferenceValues

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = transform(preferenceValues.value).also { preferenceValues.value = it }
        }
        preferences = PreferencesRepository(
            preferenceDataStore,
            store,
        )
        repository = SyncRepository(
            api = api,
            store = store,
            accountData = AccountDataNamespace(database, Json),
            database = database,
            subscriptions = database.subscriptionDao(),
            favorites = database.favoriteDao(),
            playbackStates = database.playbackStateDao(),
            listeningSessions = database.listeningSessionDao(),
            tombstones = database.tombstoneDao(),
            queue = database.queueDao(),
            podcastSettings = database.podcastSettingsDao(),
            preferences = preferences,
            downloads = DownloadRepository(
                context = context,
                dao = database.episodeDownloadDao(),
                clock = object : Clock { override fun nowMs() = 1L },
                accountStore = store,
                preferences = preferences,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `push contains full records and deletion tombstones with stable ids`() = runTest {
        database.subscriptionDao().upsert(
            SubscriptionEntity(
                podcastId = "show",
                feedUrl = "https://example.com/feed.xml",
                title = "Show",
                artworkUrl = "https://example.com/cover.jpg",
                addedAt = 123,
                inboxMode = SubscriptionEntity.INBOX_MODE_LATEST,
            ),
        )
        database.tombstoneDao().upsert(
            TombstoneEntity(
                id = "favorite:gone",
                entityType = "favorite",
                entityId = "gone",
                deletedAt = 456,
            ),
        )

        val operations = repository.buildOperations("device")

        assertEquals(2, operations.size)
        val subscription = operations.first { it.entityType == "subscription" }
        assertEquals("s:show:123", subscription.clientOpId)
        assertEquals("latest", subscription.payload.toString()
            .substringAfter("\"inbox_mode\":\"").substringBefore('"'))
        val deletion = operations.first { it.action == "delete" }
        assertEquals("d:favorite:gone:456", deletion.clientOpId)
        assertEquals("gone", deletion.entityId)
        assertTrue(deletion.payload.toString() == "{}")
    }

    @Test
    fun `push skips unresolved OPML subscriptions`() = runTest {
        val feedUrl = "https://example.com/imported.xml"
        database.subscriptionDao().upsert(
            SubscriptionEntity(
                podcastId = feedUrl,
                feedUrl = feedUrl,
                title = "Imported",
                artworkUrl = "",
                addedAt = 123,
            ),
        )

        assertTrue(repository.buildOperations("device").isEmpty())
    }

    @Test
    fun `historical listening sessions use their own backfill watermark`() {
        val historicalSession = syncOperation("listening_session", 100)
        val historicalSubscription = syncOperation("subscription", 100)

        val pending = pendingSyncOperations(
            operations = listOf(historicalSession, historicalSubscription),
            generalWatermarks = mapOf("subscription" to 500),
            listeningWatermark = 0,
        )

        assertEquals(listOf(historicalSession), pending)
        assertTrue(
            pendingSyncOperations(
                operations = listOf(historicalSession),
                generalWatermarks = emptyMap(),
                listeningWatermark = 100,
            ).isEmpty(),
        )
    }

    @Test
    fun `operation building does not rescan already uploaded listening history`() = runTest {
        database.listeningSessionDao().upsert(listeningSession("old", endedAt = 100))
        database.listeningSessionDao().upsert(listeningSession("new", endedAt = 600))

        val operations = repository.buildOperations("device", listeningAfter = 500)

        assertEquals(listOf("new"), operations.filter { it.entityType == "listening_session" }.map { it.entityId })
    }

    @Test
    fun `pull follows next cursor while server says more pages exist`() = runTest {
        val requestedCursors = mutableListOf<Long>()
        apiHandler = { method, args ->
            when (method.name) {
                "pullSync" -> {
                    val cursor = args!![0] as Long
                    assertEquals(500, args[1])
                    requestedCursors += cursor
                    if (cursor == 0L) {
                        Response.success(
                            SyncPullResponse(
                                sinceCursor = 0,
                                nextCursor = 20,
                                currentCursor = 30,
                                hasMore = true,
                                changesets = listOf(subscriptionChange("first", 20)),
                            ),
                        )
                    } else {
                        Response.success(
                            SyncPullResponse(
                                sinceCursor = 20,
                                nextCursor = 30,
                                currentCursor = 30,
                                hasMore = false,
                                changesets = listOf(subscriptionChange("second", 30)),
                            ),
                        )
                    }
                }
                else -> error("unexpected API call: ${method.name}")
            }
        }

        repository.pull(USER_ID)

        assertEquals(listOf(0L, 20L), requestedCursors)
        assertEquals(30L, store.cursor(USER_ID))
        assertEquals(setOf("first", "second"), database.subscriptionDao().getAll().map { it.podcastId }.toSet())
    }

    @Test
    fun `newer server data generation clears old local copy before changes are applied`() = runTest {
        val downloadedFile = File.createTempFile("stale-download-", ".mp3")
        database.subscriptionDao().upsert(
            SubscriptionEntity(
                podcastId = "stale",
                feedUrl = "https://old.example/feed.xml",
                title = "Stale",
                artworkUrl = "",
                addedAt = 1,
            ),
        )
        database.episodeDownloadDao().upsert(
            EpisodeDownloadEntity(
                episodeId = "stale-episode",
                podcastId = "stale",
                title = "Stale episode",
                podcastTitle = "Stale",
                artworkUrl = "",
                enclosureUrl = "https://old.example/episode.mp3",
                durationMs = 1,
                categories = emptyList(),
                state = DownloadState.DONE.name,
                bytesDownloaded = downloadedFile.length(),
                totalBytes = downloadedFile.length(),
                localPath = downloadedFile.absolutePath,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        store.setCursor(USER_ID, 44)
        store.setPushWatermark(USER_ID, 55)
        preferences.setAllowExplicitContent(true)
        preferences.setThemeMode(net.koalastuff.koalacast.core.model.ThemeMode.LIGHT)
        preferences.setSettingsFieldTimestamps(mapOf("theme_mode" to 123))
        apiHandler = { method, _ ->
            when (method.name) {
                "pullSync" -> Response.success(
                    SyncPullResponse(
                        sinceCursor = 44,
                        nextCursor = 45,
                        currentCursor = 45,
                        dataGeneration = 1,
                        changesets = listOf(subscriptionChange("must-not-apply", 45)),
                    ),
                )
                else -> error("old client attempted ${method.name} after reset")
            }
        }

        val stopped = runCatching { repository.pull(USER_ID) }.exceptionOrNull()

        assertTrue(stopped != null)
        assertTrue(database.subscriptionDao().getAll().isEmpty())
        assertTrue(database.episodeDownloadDao().getAllOldestFirst().isEmpty())
        assertTrue(!downloadedFile.exists())
        assertEquals(1L, store.dataGeneration(USER_ID))
        assertEquals(0L, store.cursor(USER_ID))
        assertEquals(0L, store.pushWatermark(USER_ID))
        assertTrue(!preferences.preferences.first().allowExplicitContent)
        assertEquals(
            net.koalastuff.koalacast.core.model.ThemeMode.SYSTEM,
            preferences.preferences.first().themeMode,
        )
        assertTrue(preferences.settingsFieldTimestamps().isEmpty())
    }

    @Test
    fun `410 replaces synced tables from one snapshot and resumes once`() = runTest {
        database.subscriptionDao().upsert(
            SubscriptionEntity(
                podcastId = "stale",
                feedUrl = "https://old.example/feed.xml",
                title = "Stale",
                artworkUrl = "",
                addedAt = 1,
            ),
        )
        database.listeningSessionDao().upsert(
            ListeningSessionEntity(
                id = "local-unsynced",
                episodeId = "local-episode",
                podcastId = "local-show",
                title = "Local episode",
                podcastTitle = "Local show",
                startedAt = 2,
                endedAt = 3,
                wallClockMs = 1,
                audioListenedMs = 1,
                speedSavedMs = 0,
                silenceSavedMs = 0,
                manualSkippedMs = 0,
                introOutroSkippedMs = 0,
                speedWeightedMs = 1,
            ),
        )
        database.tombstoneDao().upsert(
            TombstoneEntity(
                id = "subscription:fresh",
                entityType = "subscription",
                entityId = "fresh",
                deletedAt = 50,
            ),
        )
        store.setCursor(USER_ID, 12)
        store.setPushWatermark(USER_ID, 40)
        var pullCalls = 0
        var snapshotCalls = 0
        apiHandler = { method, args ->
            when (method.name) {
                "pullSync" -> {
                    pullCalls++
                    if ((args!![0] as Long) == 12L) {
                        Response.error<SyncPullResponse>(410, "".toResponseBody())
                    } else {
                        assertEquals(90L, args[0])
                        Response.success(
                            SyncPullResponse(
                                sinceCursor = 90,
                                nextCursor = 90,
                                currentCursor = 90,
                                hasMore = false,
                            ),
                        )
                    }
                }
                "syncSnapshot" -> {
                    snapshotCalls++
                    Response.success(
                        SyncSnapshotResponse(
                            cursor = 90,
                            subscriptions = listOf(
                                buildJsonObject {
                                    put("podcast_id", "fresh")
                                    put("feed_url", "https://new.example/feed.xml")
                                    put("title", "Fresh")
                                    put("artwork_url", "https://new.example/art.jpg")
                                    put("added_at", 10)
                                },
                            ),
                            favorites = listOf(
                                buildJsonObject {
                                    put("episode_id", "favorite")
                                    put("added_at", 11)
                                },
                            ),
                            playbackStates = listOf(
                                buildJsonObject {
                                    put("episode_id", "episode")
                                    put("podcast_id", "fresh")
                                    put("position_ms", 42_000)
                                    put("last_played_at", 12)
                                },
                            ),
                            listeningSessions = listOf(
                                buildJsonObject {
                                    put("id", "session")
                                    put("episode_id", "episode")
                                    put("podcast_id", "fresh")
                                    put("started_at", 10)
                                    put("ended_at", 20)
                                    put("wall_clock_ms", 10)
                                    put("audio_listened_ms", 10)
                                },
                            ),
                        ),
                    )
                }
                else -> error("unexpected API call: ${method.name}")
            }
        }

        repository.pull(USER_ID)

        assertEquals(2, pullCalls)
        assertEquals(1, snapshotCalls)
        assertEquals(90L, store.cursor(USER_ID))
        assertEquals(listOf("stale"), database.subscriptionDao().getAll().map { it.podcastId })
        assertEquals("fresh", database.tombstoneDao().get("subscription:fresh")?.entityId)
        assertEquals(listOf("favorite"), database.favoriteDao().getAll().map { it.episodeId })
        assertEquals(42_000L, database.playbackStateDao().get("episode")!!.positionMs)
        assertEquals(
            setOf("local-unsynced", "session"),
            database.listeningSessionDao().getAll().map { it.id }.toSet(),
        )
        assertEquals(20L, database.listeningSessionDao().get("session")!!.endedAt)
    }

    @Test
    fun `a second 410 after snapshot fails without loading another snapshot`() = runTest {
        var snapshotCalls = 0
        apiHandler = { method, _ ->
            when (method.name) {
                "pullSync" -> Response.error<SyncPullResponse>(410, "".toResponseBody())
                "syncSnapshot" -> {
                    snapshotCalls++
                    Response.success(SyncSnapshotResponse(cursor = 50))
                }
                else -> error("unexpected API call: ${method.name}")
            }
        }

        val failure = runCatching { repository.pull(USER_ID) }.exceptionOrNull()

        assertTrue(failure?.message?.contains("after snapshot recovery") == true)
        assertEquals(1, snapshotCalls)
    }

    @Test
    fun `one rejected operation is isolated and the rest still sync`() = runTest {
        // The failure this guards: a 400 aborted the whole push, the watermark
        // never moved, and the next attempt resent the same rejected operation —
        // so one unacceptable row kept an entire account's data off the server
        // for good.
        repeat(6) { index ->
            database.listeningSessionDao().upsert(
                ListeningSessionEntity(
                    id = "session-$index",
                    episodeId = "episode-$index",
                    podcastId = "show",
                    title = "Episode $index",
                    podcastTitle = "Show",
                    startedAt = 10L + index,
                    endedAt = 20L + index,
                    wallClockMs = 10,
                    audioListenedMs = 10,
                    speedSavedMs = 0,
                    silenceSavedMs = 0,
                    manualSkippedMs = 0,
                    introOutroSkippedMs = 0,
                    speedWeightedMs = 10,
                ),
            )
        }

        val accepted = mutableListOf<String>()
        apiHandler = { method, args ->
            when (method.name) {
                "pushSync" -> {
                    val body = args!![0] as SyncPushRequest
                    assertEquals(store.dataGeneration(USER_ID), body.dataGeneration)
                    val poisoned = body.operations.any { it.entityId == "session-3" }
                    if (poisoned && body.operations.size == 1) {
                        Response.error<SyncPushResponse>(
                            400,
                            """{"error":"invalid operation at index 0: bad"}""".toResponseBody(),
                        )
                    } else if (poisoned) {
                        Response.error<SyncPushResponse>(400, "".toResponseBody())
                    } else {
                        accepted += body.operations.map { it.entityId }
                        Response.success(SyncPushResponse(appliedOps = body.operations.size))
                    }
                }
                else -> error("unexpected API call: ${method.name}")
            }
        }

        val rejected = repository.push(USER_ID, "device", store.accountGeneration())

        // Everything except the poisoned record reached the server.
        assertTrue(accepted.containsAll(listOf("session-0", "session-1", "session-2", "session-4", "session-5")))
        assertTrue("session-3" !in accepted)
        // And it is reported rather than vanishing quietly.
        assertEquals(1, rejected.size)
        assertTrue(rejected.first().contains("session-3"))
    }

    private fun subscriptionChange(id: String, cursor: Long) = SyncChangesetDto(
        entityType = "subscription",
        entityId = id,
        action = "upsert",
        payload = buildJsonObject {
            put("podcast_id", id)
            put("feed_url", "https://example.com/$id.xml")
            put("title", id)
            put("added_at", cursor)
        },
        clientTimestamp = cursor,
        serverCursor = cursor,
    )

    private fun syncOperation(type: String, timestamp: Long) = SyncOperationDto(
        clientOpId = "$type:$timestamp",
        deviceId = "device",
        entityType = type,
        action = "upsert",
        entityId = type,
        payload = JsonObject(emptyMap()),
        clientTimestamp = timestamp,
    )

    private fun listeningSession(id: String, endedAt: Long) = ListeningSessionEntity(
        id = id,
        episodeId = "episode-$id",
        podcastId = "show",
        title = "Episode",
        podcastTitle = "Show",
        startedAt = endedAt - 10,
        endedAt = endedAt,
        wallClockMs = 10,
        audioListenedMs = 10,
    )

    private companion object {
        const val USER_ID = "user"
    }
}

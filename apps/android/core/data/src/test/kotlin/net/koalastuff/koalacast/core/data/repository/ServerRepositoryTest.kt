package net.koalastuff.koalacast.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.db.KoalaCastDatabase
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.network.KoalaCastApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
class ServerRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var preferencesFile: File
    private lateinit var repository: ServerRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferencesFile = File(context.cacheDir, "server-test-${UUID.randomUUID()}.preferences_pb")
        val accountStore = SecureAccountStore(context)
        val preferences = PreferencesRepository(
            PreferenceDataStoreFactory.create { preferencesFile },
            accountStore,
        )
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
        // Keep the candidate origin HTTPS in every variant. The interceptor only
        // transports the test request to MockWebServer; production validation and
        // URL construction still see the TLS origin.
        val mockTransport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    request.newBuilder()
                        .url(server.url(request.url.encodedPath))
                        .build(),
                )
            }
            .build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(mockTransport)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KoalaCastApi::class.java)
        repository = ServerRepository(
            api = api,
            preferences = preferences,
            accountStore = accountStore,
            accountData = AccountDataNamespace(
                Room.inMemoryDatabaseBuilder(context, KoalaCastDatabase::class.java)
                    .allowMainThreadQueries()
                    .build(),
                json,
            ),
            dispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        server.close()
        preferencesFile.delete()
    }

    @Test
    fun `validation accepts healthy and database-ready instance`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"ok","version":"1.2.3"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"status":"ready","database":"connected"}""")
                .build(),
        )

        val origin = "https://test.koalacast.invalid"
        val result = repository.validate(origin)

        assertTrue(result is DataResult.Success)
        assertEquals(origin, (result as DataResult.Success).data)
        assertEquals("/api/v1/healthz", server.takeRequest().url.encodedPath)
        assertEquals("/api/v1/readyz", server.takeRequest().url.encodedPath)
    }
}

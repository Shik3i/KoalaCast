package net.koalastuff.koalacast.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Cache
import android.content.Context
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The client that talks to `/api/v1`. Its requests are re-pointed at the configured
 * server by [HostSelectionInterceptor].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // Feeds evolve and a self-hoster may run an older server than the app.
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * The unqualified client carries no host rewriting, which matters for artwork:
     * with the image proxy switched off, covers are fetched from the publisher's own
     * CDN and must not be redirected at the KoalaCast host.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient =
        OkHttpClient.Builder()
            // Public GETs carry ETags. Keep their bodies on disk so a
            // Cache-Control: no-cache request becomes a tiny conditional probe
            // and OkHttp can combine a 304 with the last body.
            .cache(Cache(context.cacheDir.resolve("koalacast-http"), 50L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /** Shares the base client's pool and dispatcher, adds the host rewriting. */
    @Provides
    @Singleton
    @ApiClient
    fun provideApiClient(
        base: OkHttpClient,
        hostSelection: HostSelectionInterceptor,
        auth: AuthInterceptor,
        requestTimeout: RequestTimeoutInterceptor,
    ): OkHttpClient = base.newBuilder()
        .addInterceptor(hostSelection)
        .addInterceptor(auth)
        .addInterceptor(requestTimeout)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(@ApiClient client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            // Placeholder: every request is re-pointed by HostSelectionInterceptor.
            .baseUrl("http://server.invalid/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideKoalaCastApi(retrofit: Retrofit): KoalaCastApi =
        retrofit.create(KoalaCastApi::class.java)
}

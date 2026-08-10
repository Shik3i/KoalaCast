package net.koalastuff.koalacast.core.network

import net.koalastuff.koalacast.core.network.dto.AddFeedRequest
import net.koalastuff.koalacast.core.network.dto.ChaptersResponse
import net.koalastuff.koalacast.core.network.dto.DeleteAccountRequest
import net.koalastuff.koalacast.core.network.dto.DiscoverResponse
import net.koalastuff.koalacast.core.network.dto.EpisodeDto
import net.koalastuff.koalacast.core.network.dto.EpisodesResponse
import net.koalastuff.koalacast.core.network.dto.HealthResponse
import net.koalastuff.koalacast.core.network.dto.ReadinessResponse
import net.koalastuff.koalacast.core.network.dto.PodcastDto
import net.koalastuff.koalacast.core.network.dto.SearchResponse
import net.koalastuff.koalacast.core.network.dto.AuthMessageResponse
import net.koalastuff.koalacast.core.network.dto.AuthStatusResponse
import net.koalastuff.koalacast.core.network.dto.DeviceLoginRequest
import net.koalastuff.koalacast.core.network.dto.DeviceLoginResponse
import net.koalastuff.koalacast.core.network.dto.GlobalStatsPreference
import net.koalastuff.koalacast.core.network.dto.OpmlImportReport
import net.koalastuff.koalacast.core.network.dto.RecoveryRequest
import net.koalastuff.koalacast.core.network.dto.RegisterRequest
import net.koalastuff.koalacast.core.network.dto.RegisterResponse
import net.koalastuff.koalacast.core.network.dto.SessionsResponse
import net.koalastuff.koalacast.core.network.dto.SyncPullResponse
import net.koalastuff.koalacast.core.network.dto.SyncPushRequest
import net.koalastuff.koalacast.core.network.dto.SyncPushResponse
import net.koalastuff.koalacast.core.network.dto.SyncSnapshotResponse
import net.koalastuff.koalacast.core.network.dto.GlobalStatsDto
import net.koalastuff.koalacast.core.network.dto.TranscriptContentDto
import okhttp3.RequestBody
import retrofit2.http.DELETE
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * The unauthenticated slice of `/api/v1` — everything P0/P1 needs. Account and sync
 * endpoints arrive with the device-token work (see apps/android/README.md §2.3).
 *
 * Requests carry a relative path; [HostSelectionInterceptor] rewrites them onto the
 * server the listener picked, so there is no static base URL anywhere in the app.
 */
interface KoalaCastApi {

    @GET("api/v1/healthz")
    suspend fun healthz(): Response<HealthResponse>

    /** Probes an arbitrary origin during server selection, before it is saved. */
    // Literal because annotation arguments must be constants — keep in sync with
    // HostSelectionInterceptor.ABSOLUTE_HEADER.
    @Headers("X-KoalaCast-Absolute-Url: 1")
    @GET
    suspend fun healthzAt(@Url absoluteUrl: String): Response<HealthResponse>

    @Headers("X-KoalaCast-Absolute-Url: 1")
    @GET
    suspend fun readyzAt(@Url absoluteUrl: String): Response<ReadinessResponse>

    @Headers("Cache-Control: no-cache")
    @GET("api/v1/podcasts/discover")
    suspend fun discover(
        @Query("category") category: String? = null,
        @Query("region") region: String? = null,
        @Query("languages") languages: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<DiscoverResponse>

    @Headers("Cache-Control: no-cache")
    @GET("api/v1/podcasts/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("languages") languages: String? = null,
        @Query("category") category: String? = null,
        @Query("region") region: String? = null,
    ): Response<SearchResponse>

    /** Resolves (and, if new, ingests) a feed URL into a canonical KoalaCast podcast. */
    @POST("api/v1/podcasts/feed")
    suspend fun addFeed(@Body body: AddFeedRequest): Response<PodcastDto>

    @Headers("Cache-Control: no-cache")
    @GET("api/v1/podcasts/{id}")
    suspend fun podcast(@Path("id") id: String): Response<PodcastDto>

    @Headers("Cache-Control: no-cache")
    @GET("api/v1/podcasts/{id}/episodes")
    suspend fun episodes(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("since") since: Long? = null,
    ): Response<EpisodesResponse>

    @Headers("Cache-Control: no-cache")
    @GET("api/v1/episodes/{id}")
    suspend fun episode(@Path("id") id: String): Response<EpisodeDto>

    /**
     * Chapter JSON lives on the publisher's host, so it goes through the server's
     * proxy: that keeps the listener's address off a third-party origin, exactly as
     * artwork proxying does.
     */
    @Headers("Cache-Control: no-cache")
    @GET("api/v1/proxy/chapters")
    suspend fun chapters(@Query("url") url: String): Response<ChaptersResponse>

    @Headers("Cache-Control: no-cache")
    @GET("api/v1/episodes/{id}/transcript")
    suspend fun transcript(
        @Path("id") id: String,
        @Query("i") index: Int = 0,
    ): Response<TranscriptContentDto>

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<RegisterResponse>

    @POST("api/v1/auth/device/login")
    suspend fun deviceLogin(@Body body: DeviceLoginRequest): Response<DeviceLoginResponse>

    @POST("api/v1/auth/recovery/verify")
    suspend fun recover(@Body body: RecoveryRequest): Response<AuthMessageResponse>

    @GET("api/v1/auth/status")
    suspend fun authStatus(): Response<AuthStatusResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(): Response<AuthMessageResponse>

    @GET("api/v1/auth/sessions")
    suspend fun sessions(): Response<SessionsResponse>

    @DELETE("api/v1/auth/sessions/{id}")
    suspend fun revokeSession(@Path("id") id: String): Response<AuthMessageResponse>

    /**
     * Irreversible. Carries the credential again even though the request is
     * already authenticated, so a borrowed unlocked phone cannot delete an
     * account in two taps. HTTP method with a body, hence @HTTP rather than
     * @DELETE, which Retrofit does not allow one on.
     */
    @HTTP(method = "DELETE", path = "api/v1/auth/account", hasBody = true)
    suspend fun deleteAccount(@Body body: DeleteAccountRequest): Response<Unit>

    @GET("api/v1/sync")
    suspend fun pullSync(
        @Query("since_cursor") sinceCursor: Long,
        @Query("limit") limit: Int = 500,
    ): Response<SyncPullResponse>

    @GET("api/v1/sync/snapshot")
    suspend fun syncSnapshot(): Response<SyncSnapshotResponse>

    @POST("api/v1/sync")
    suspend fun pushSync(@Body body: SyncPushRequest): Response<SyncPushResponse>

    @GET("api/v1/stats/preferences")
    suspend fun statsPreference(): Response<GlobalStatsPreference>

    @PUT("api/v1/stats/preferences")
    suspend fun updateStatsPreference(@Body body: GlobalStatsPreference): Response<GlobalStatsPreference>

    @Headers("Content-Type: application/xml", "X-KoalaCast-Long-Request: 1")
    @POST("api/v1/opml/import")
    suspend fun importOpml(@Body body: RequestBody): Response<OpmlImportReport>

    @GET("api/v1/stats/global")
    suspend fun globalStats(@Query("range") range: String): Response<GlobalStatsDto>
}

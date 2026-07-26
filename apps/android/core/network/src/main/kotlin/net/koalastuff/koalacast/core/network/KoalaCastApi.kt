package net.koalastuff.koalacast.core.network

import net.koalastuff.koalacast.core.network.dto.AddFeedRequest
import net.koalastuff.koalacast.core.network.dto.DiscoverResponse
import net.koalastuff.koalacast.core.network.dto.EpisodeDto
import net.koalastuff.koalacast.core.network.dto.EpisodesResponse
import net.koalastuff.koalacast.core.network.dto.HealthResponse
import net.koalastuff.koalacast.core.network.dto.PodcastDto
import net.koalastuff.koalacast.core.network.dto.SearchResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
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

    @GET("api/v1/podcasts/discover")
    suspend fun discover(
        @Query("category") category: String? = null,
        @Query("region") region: String? = null,
        @Query("languages") languages: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<DiscoverResponse>

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

    @GET("api/v1/podcasts/{id}")
    suspend fun podcast(@Path("id") id: String): Response<PodcastDto>

    @GET("api/v1/podcasts/{id}/episodes")
    suspend fun episodes(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): Response<EpisodesResponse>

    @GET("api/v1/episodes/{id}")
    suspend fun episode(@Path("id") id: String): Response<EpisodeDto>
}

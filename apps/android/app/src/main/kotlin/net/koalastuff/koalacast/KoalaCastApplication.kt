package net.koalastuff.koalacast

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import javax.inject.Inject
import net.koalastuff.koalacast.core.data.repository.AppShortcuts
import net.koalastuff.koalacast.core.data.repository.AutoDownloadWorker
import net.koalastuff.koalacast.core.data.repository.ContentRefreshWorker
import net.koalastuff.koalacast.core.data.repository.SyncCoordinator
import net.koalastuff.koalacast.core.data.repository.LibraryRepository
import net.koalastuff.koalacast.core.data.repository.AccountDataNamespace
import net.koalastuff.koalacast.core.data.repository.AppReadiness
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.server.ArtworkUrls
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

@HiltAndroidApp
class KoalaCastApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The same client the API uses, so artwork requests inherit its timeouts and — for
     * proxied covers — the server the listener picked.
     */
    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var syncCoordinator: SyncCoordinator

    @Inject
    lateinit var appShortcuts: AppShortcuts

    @Inject
    lateinit var library: LibraryRepository

    @Inject
    lateinit var artworkUrls: ArtworkUrls

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var accountData: AccountDataNamespace

    @Inject
    lateinit var accountStore: SecureAccountStore

    @Inject
    lateinit var preferences: PreferencesRepository

    @Inject
    lateinit var appReadiness: AppReadiness

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Media3 owns Cast session management and output transfers. Initialising
        // here makes the route button usable before playback starts; failures are
        // non-fatal on devices without Google Play services.
        runCatching { Cast.getSingletonInstance(this).initialize() }
        applicationScope.launch {
            accountStore.setServerOrigin(preferences.serverUrl.first())
            val account = accountStore.account.value
            val ownerId = accountStore.activeOwnerId()
            accountData.initialize(ownerId, account?.userId)
            account?.let {
                accountStore.migrateLegacySyncState(it.userId)
                preferences.migrateUserScope(it.userId, ownerId)
            }
            preferences.migrateLegacyForCurrentOwner()
            appReadiness.markReady()
            syncCoordinator.start()
            // Idempotent (KEEP policy), so registering on every start costs nothing
            // and survives a reboot or an app update clearing the schedule.
            AutoDownloadWorker.schedule(this@KoalaCastApplication)
            ContentRefreshWorker.schedule(this@KoalaCastApplication)
            appShortcuts.refresh()
            library.allSubscriptions
                .map { subscriptions ->
                    subscriptions.map { it.artworkUrl }.filter(String::isNotBlank).distinct()
                }
                .distinctUntilChanged()
                .collect { rawUrls ->
                    val loader = SingletonImageLoader.get(this@KoalaCastApplication)
                    rawUrls.forEach { rawUrl ->
                        SUBSCRIPTION_ARTWORK_DP.forEach { artworkDp ->
                            val artworkPx = (
                                artworkDp * resources.displayMetrics.density
                            ).roundToInt()
                            val url = artworkUrls.forArtworkReady(rawUrl, artworkPx)
                                ?: return@forEach
                            loader.enqueue(
                                ImageRequest.Builder(this@KoalaCastApplication)
                                    .data(url)
                                    .size(Size(artworkPx, artworkPx))
                                    .build(),
                            )
                        }
                    }
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("artwork"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    private companion object {
        val SUBSCRIPTION_ARTWORK_DP = intArrayOf(40, 56, 160)
    }
}

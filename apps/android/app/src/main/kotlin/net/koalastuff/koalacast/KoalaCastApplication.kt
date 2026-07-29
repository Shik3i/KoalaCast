package net.koalastuff.koalacast

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.server.ArtworkUrls
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class KoalaCastApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        runBlocking(Dispatchers.IO) {
            accountData.initialize(accountStore.account.value?.userId)
            preferences.migrateLegacyForCurrentOwner()
        }
        syncCoordinator.start()
        // Idempotent (KEEP policy), so registering on every start costs nothing
        // and survives a reboot or an app update clearing the schedule.
        AutoDownloadWorker.schedule(this)
        ContentRefreshWorker.schedule(this)
        // Launcher-side cache; refreshing at start is soon enough for something
        // the listener reaches before the app is open.
        CoroutineScope(Dispatchers.IO).launch { appShortcuts.refresh() }
        CoroutineScope(Dispatchers.IO).launch {
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

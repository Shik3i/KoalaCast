package net.koalastuff.koalacast

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import net.koalastuff.koalacast.core.data.repository.AppShortcuts
import net.koalastuff.koalacast.core.data.repository.AutoDownloadWorker
import net.koalastuff.koalacast.core.data.repository.SyncCoordinator

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
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        syncCoordinator.start()
        // Idempotent (KEEP policy), so registering on every start costs nothing
        // and survives a reboot or an app update clearing the schedule.
        AutoDownloadWorker.schedule(this)
        // Launcher-side cache; refreshing at start is soon enough for something
        // the listener reaches before the app is open.
        CoroutineScope(Dispatchers.IO).launch { appShortcuts.refresh() }
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
}

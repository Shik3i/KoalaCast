package net.koalastuff.koalacast

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject
import net.koalastuff.koalacast.core.data.repository.SyncCoordinator

@HiltAndroidApp
class KoalaCastApplication : Application(), SingletonImageLoader.Factory {

    /**
     * The same client the API uses, so artwork requests inherit its timeouts and — for
     * proxied covers — the server the listener picked.
     */
    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var syncCoordinator: SyncCoordinator

    override fun onCreate() {
        super.onCreate()
        syncCoordinator.start()
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

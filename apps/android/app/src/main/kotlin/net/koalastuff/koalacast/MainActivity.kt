package net.koalastuff.koalacast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.server.ArtworkUrls
import net.koalastuff.koalacast.core.data.repository.AppReadiness
import net.koalastuff.koalacast.core.model.PaletteId
import net.koalastuff.koalacast.core.model.StartScreen
import net.koalastuff.koalacast.core.model.ThemeMode
import net.koalastuff.koalacast.core.ui.component.LocalArtworkUrls
import net.koalastuff.koalacast.core.ui.theme.KoalaCastTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var requestedEpisodeId by mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @Inject
    lateinit var preferences: PreferencesRepository

    @Inject
    lateinit var artworkUrls: ArtworkUrls

    @Inject
    lateinit var appReadiness: AppReadiness

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedEpisodeId = intent.getStringExtra(EXTRA_EPISODE_ID)

        setContent {
            val prefs by preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
            val appReady by appReadiness.ready.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Dark until the stored choice is known: the design ships dark, so
            // waiting on DataStore must not flash a light frame.
            KoalaCastTheme(
                themeMode = prefs?.themeMode ?: ThemeMode.DARK,
                palette = prefs?.palette ?: PaletteId.DEFAULT,
            ) {
                if (appReady) CompositionLocalProvider(LocalArtworkUrls provides artworkUrls) {
                    KoalaCastApp(
                        onboardingComplete = prefs?.onboardingComplete,
                        startScreen = prefs?.startScreen ?: StartScreen.DEFAULT,
                        requestedEpisodeId = requestedEpisodeId,
                        onEpisodeRequestConsumed = { requestedEpisodeId = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedEpisodeId = intent.getStringExtra(EXTRA_EPISODE_ID)
    }

    private companion object {
        const val EXTRA_EPISODE_ID = "episodeId"
    }
}

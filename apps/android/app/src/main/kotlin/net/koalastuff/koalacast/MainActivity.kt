package net.koalastuff.koalacast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.server.ArtworkUrls
import net.koalastuff.koalacast.core.model.ThemeMode
import net.koalastuff.koalacast.core.ui.component.LocalArtworkUrls
import net.koalastuff.koalacast.core.ui.theme.KoalaCastTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @Inject
    lateinit var preferences: PreferencesRepository

    @Inject
    lateinit var artworkUrls: ArtworkUrls

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val prefs by preferences.preferences.collectAsState(initial = null)

            LaunchedEffect(prefs?.onboardingComplete) {
                if (
                    prefs?.onboardingComplete == true &&
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
            KoalaCastTheme(themeMode = prefs?.themeMode ?: ThemeMode.DARK) {
                CompositionLocalProvider(LocalArtworkUrls provides artworkUrls) {
                    KoalaCastApp(
                        onboardingComplete = prefs?.onboardingComplete,
                    )
                }
            }
        }
    }
}

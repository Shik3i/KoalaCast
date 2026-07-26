package net.koalastuff.koalacast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.data.server.ArtworkUrls
import net.koalastuff.koalacast.core.model.ThemeMode
import net.koalastuff.koalacast.core.ui.component.LocalArtworkUrls
import net.koalastuff.koalacast.core.ui.theme.KoalaCastTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferences: PreferencesRepository

    @Inject
    lateinit var artworkUrls: ArtworkUrls

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val prefs by preferences.preferences.collectAsState(initial = null)

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

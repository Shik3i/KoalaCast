package net.koalastuff.koalacast.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.koalastuff.koalacast.core.model.ThemeMode
import net.koalastuff.koalacast.core.model.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every preference the app has, on-device, in DataStore. There is deliberately no
 * server-side profile: an account syncs listening data, never settings.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<UserPreferences> = dataStore.data.map { it.toUserPreferences() }

    val serverUrl: Flow<String> = preferences.map { it.serverUrl }

    suspend fun setServerUrl(url: String) {
        dataStore.edit { it[Keys.SERVER_URL] = url }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setLanguages(languages: Set<String>) {
        dataStore.edit { it[Keys.LANGUAGES] = languages }
    }

    suspend fun setCategory(category: String) {
        dataStore.edit { it[Keys.CATEGORY] = category }
    }

    suspend fun setProxyImages(enabled: Boolean) {
        dataStore.edit { it[Keys.PROXY_IMAGES] = enabled }
    }

    private fun Preferences.toUserPreferences() = UserPreferences(
        serverUrl = this[Keys.SERVER_URL] ?: KoalaCastDefaults.SERVER_URL,
        onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
        themeMode = this[Keys.THEME_MODE]
            ?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
        languages = this[Keys.LANGUAGES] ?: emptySet(),
        category = this[Keys.CATEGORY].orEmpty(),
        proxyImages = this[Keys.PROXY_IMAGES] ?: true,
    )

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGES = stringSetPreferencesKey("languages")
        val CATEGORY = stringPreferencesKey("category")
        val PROXY_IMAGES = booleanPreferencesKey("proxy_images")
    }
}

object KoalaCastDefaults {
    /**
     * The instance the project runs. Self-hosters replace it in onboarding or in
     * Settings; nothing in the app assumes this particular origin.
     */
    const val SERVER_URL = "https://cast.koalastuff.net"

    /** Reaches the host machine's server from the Android emulator. */
    const val EMULATOR_LOOPBACK_URL = "http://10.0.2.2:3000"
}

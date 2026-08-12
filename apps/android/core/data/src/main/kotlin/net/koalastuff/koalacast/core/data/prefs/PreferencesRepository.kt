package net.koalastuff.koalacast.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import net.koalastuff.koalacast.core.data.repository.SyncedSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.server.ServerUrl
import net.koalastuff.koalacast.core.model.DownloadRetention
import net.koalastuff.koalacast.core.model.DownloadStorage
import net.koalastuff.koalacast.core.model.HiddenPodcast
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.PaletteId
import net.koalastuff.koalacast.core.model.StartScreen
import net.koalastuff.koalacast.core.model.ThemeMode
import net.koalastuff.koalacast.core.model.VisualizerStyle
import net.koalastuff.koalacast.core.model.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every preference the app has, on-device, in DataStore. Portable UI, playback,
 * and download policy preferences sync after sign-in; server URL, onboarding,
 * and a device-specific SAF path deliberately stay local.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val accountStore: SecureAccountStore,
) {
    val preferences: Flow<UserPreferences> = combine(dataStore.data, accountStore.account) { values, account ->
        values.toUserPreferences(account?.let { accountStore.activeOwnerId() })
    }

    val serverUrl: Flow<String> = preferences.map { it.serverUrl }
    val insecureServerResetPending: Flow<Boolean> = dataStore.data.map {
        it[Keys.INSECURE_SERVER_RESET_PENDING] ?: false
    }

    suspend fun setServerUrl(url: String) {
        val safeUrl = requireNotNull(ServerUrl.normalise(url)) { "Server URL violates transport policy" }
        dataStore.edit { it[Keys.SERVER_URL] = safeUrl }
    }

    /**
     * Runs before Auth/Sync startup. Invalid stored HTTP origins are replaced atomically,
     * and a durable UI notice remains until the listener acknowledges it.
     */
    suspend fun resetInsecureServerUrlIfNeeded(): Boolean {
        var reset = false
        dataStore.edit { values ->
            val stored = values[Keys.SERVER_URL] ?: KoalaCastDefaults.SERVER_URL
            val sanitized = ServerUrl.sanitizeStored(stored, KoalaCastDefaults.SERVER_URL)
            if (sanitized.resetFromCleartext) {
                values[Keys.SERVER_URL] = sanitized.value
                values[Keys.INSECURE_SERVER_RESET_PENDING] = true
                reset = true
            }
        }
        return reset
    }

    suspend fun acknowledgeInsecureServerReset() {
        dataStore.edit { it[Keys.INSECURE_SERVER_RESET_PENDING] = false }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.themeMode(owner())] = mode.name; it.touch(owner(), "theme_mode") }
    }

    suspend fun setPalette(palette: PaletteId) {
        dataStore.edit { it[Keys.palette(owner())] = palette.id; it.touch(owner(), "palette") }
    }

    suspend fun setLanguages(languages: Set<String>) {
        dataStore.edit { it[Keys.languages(owner())] = languages; it.touch(owner(), "languages") }
    }

    suspend fun setGenrePreferences(interests: Set<String>, hiddenGenres: Set<String>) {
        dataStore.edit {
            val owner = owner()
            it[Keys.interests(owner)] = interests
            it[Keys.hiddenGenres(owner)] = hiddenGenres - interests
            it.touch(owner, "interests", "hidden_genres")
        }
    }

    suspend fun setAllowExplicitContent(enabled: Boolean) {
        dataStore.edit { it[Keys.allowExplicitContent(owner())] = enabled }
    }

    suspend fun hidePodcast(podcast: HiddenPodcast) {
        if (podcast.key.isBlank()) return
        dataStore.edit {
            val owner = owner()
            val current = it[Keys.hiddenPodcasts(owner)].orEmpty()
                .mapNotNull(::decodeHiddenPodcast)
                .filterNot { hidden -> hidden.key == podcast.key }
            it[Keys.hiddenPodcasts(owner)] =
                (current + podcast).mapTo(mutableSetOf(), ::encodeHiddenPodcast)
            it.touch(owner, "hidden_podcasts")
        }
    }

    suspend fun unhidePodcast(key: String) {
        dataStore.edit {
            val owner = owner()
            it[Keys.hiddenPodcasts(owner)] = it[Keys.hiddenPodcasts(owner)].orEmpty()
                .mapNotNull(::decodeHiddenPodcast)
                .filterNot { hidden -> hidden.key == key }
                .mapTo(mutableSetOf(), ::encodeHiddenPodcast)
            it.touch(owner, "hidden_podcasts")
        }
    }

    suspend fun setDefaultInboxMode(mode: InboxMode) {
        dataStore.edit {
            it[Keys.defaultInboxMode(owner())] = mode.name.lowercase()
            it.touch(owner(), "default_inbox_mode")
        }
    }

    suspend fun setStartScreen(screen: StartScreen) {
        dataStore.edit { it[Keys.startScreen(owner())] = screen.id; it.touch(owner(), "start_screen") }
    }

    suspend fun setVisualizer(style: VisualizerStyle) {
        dataStore.edit { it[Keys.visualizer(owner())] = style.id; it.touch(owner(), "visualizer") }
    }

    suspend fun setProxyImages(enabled: Boolean) {
        dataStore.edit { it[Keys.proxyImages(owner())] = enabled; it.touch(owner(), "proxy_images") }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { it[Keys.playbackSpeed(owner())] = speed.coerceIn(0.5f, 3f); it.touch(owner(), "playback_speed") }
    }

    suspend fun setDownloadWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.downloadWifiOnly(owner())] = enabled; it.touch(owner(), "download_wifi_only") }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        dataStore.edit { it[Keys.skipSilence(owner())] = enabled; it.touch(owner(), "skip_silence") }
    }

    suspend fun setVolumeBoost(enabled: Boolean) {
        dataStore.edit { it[Keys.volumeBoost(owner())] = enabled; it.touch(owner(), "volume_boost") }
    }

    suspend fun setAutoDownloadCount(count: Int) {
        dataStore.edit { it[Keys.autoDownloadCount(owner())] = count.coerceIn(1, 20); it.touch(owner(), "auto_download_count") }
    }

    suspend fun setDownloadRetention(retention: DownloadRetention) {
        dataStore.edit { it[Keys.downloadRetention(owner())] = retention.id; it.touch(owner(), "download_retention") }
    }

    suspend fun setDownloadConcurrency(value: Int) {
        dataStore.edit { it[Keys.downloadConcurrency(owner())] = value.coerceIn(1, 4); it.touch(owner(), "download_concurrency") }
    }

    suspend fun setDownloadBudgetBytes(value: Long) {
        dataStore.edit {
            it[Keys.downloadBudgetMb(owner())] = (value / MB).toInt().coerceAtLeast(0)
            it.touch(owner(), "download_budget_bytes")
        }
    }

    suspend fun setDownloadStorage(storage: DownloadStorage, treeUri: String = "") {
        dataStore.edit {
            it[Keys.DOWNLOAD_STORAGE] = storage.id
            if (treeUri.isNotBlank()) it[Keys.DOWNLOAD_TREE_URI] = treeUri
        }
    }

    /**
     * The keys of the synced settings blob that this client does not understand —
     * the web client's `date_format` and `ui_language` today, whatever ships next
     * tomorrow. Stored verbatim so a push can hand them back untouched instead of
     * deleting them from the server, which is what rebuilding the whole payload
     * from known fields used to do.
     */
    suspend fun foreignSettings(): String =
        dataStore.data.first()[Keys.foreignSettings(owner())].orEmpty()

    suspend fun setForeignSettings(json: String) {
        dataStore.edit { it[Keys.foreignSettings(owner())] = json }
    }

    /** When each individual setting last changed on this device. */
    suspend fun settingsFieldTimestamps(): Map<String, Long> =
        SyncedSettings.parseTimestamps(
            dataStore.data.first()[Keys.settingsFieldUpdatedAt(owner())].orEmpty(),
        )

    suspend fun setSettingsFieldTimestamps(values: Map<String, Long>) {
        dataStore.edit {
            it[Keys.settingsFieldUpdatedAt(owner())] = encodeFieldTimestamps(values)
        }
    }

    suspend fun syncSnapshot(): Pair<UserPreferences, Long> {
        val values = dataStore.data.first()
        val owner = owner()
        return values.toUserPreferences(owner) to (values[Keys.settingsUpdatedAt(owner)] ?: 0)
    }

    suspend fun applySynced(preferences: UserPreferences, updatedAt: Long, force: Boolean = false) {
        dataStore.edit {
            val owner = owner()
            if (!force && (it[Keys.settingsUpdatedAt(owner)] ?: 0) >= updatedAt) return@edit
            it[Keys.themeMode(owner)] = preferences.themeMode.name
            it[Keys.palette(owner)] = preferences.palette.id
            it[Keys.languages(owner)] = preferences.languages
            it[Keys.interests(owner)] = preferences.interests
            it[Keys.hiddenGenres(owner)] = preferences.hiddenGenres - preferences.interests
            it[Keys.hiddenPodcasts(owner)] =
                preferences.hiddenPodcasts.mapTo(mutableSetOf(), ::encodeHiddenPodcast)
            it[Keys.defaultInboxMode(owner)] = preferences.defaultInboxMode.name.lowercase()
            it[Keys.startScreen(owner)] = preferences.startScreen.id
            it[Keys.visualizer(owner)] = preferences.visualizer.id
            it[Keys.proxyImages(owner)] = preferences.proxyImages
            it[Keys.playbackSpeed(owner)] = preferences.playbackSpeed.coerceIn(0.5f, 3f)
            it[Keys.downloadWifiOnly(owner)] = preferences.downloadWifiOnly
            it[Keys.skipSilence(owner)] = preferences.skipSilence
            it[Keys.volumeBoost(owner)] = preferences.volumeBoost
            it[Keys.autoDownloadCount(owner)] = preferences.autoDownloadCount.coerceIn(1, 20)
            it[Keys.downloadRetention(owner)] = preferences.downloadRetention.id
            it[Keys.downloadConcurrency(owner)] = preferences.downloadConcurrency.coerceIn(1, 4)
            it[Keys.downloadBudgetMb(owner)] = (preferences.downloadBudgetBytes / MB).toInt()
            // Only ever forward: fields of ours that are newer than this payload are
            // still waiting to be pushed and have to keep looking newer than it.
            it[Keys.settingsUpdatedAt(owner)] =
                maxOf(it[Keys.settingsUpdatedAt(owner)] ?: 0, updatedAt)
        }
    }

    suspend fun resetSynced() {
        dataStore.edit {
            val owner = owner()
            it.remove(Keys.themeMode(owner))
            it.remove(Keys.palette(owner))
            it.remove(Keys.languages(owner))
            it.remove(Keys.interests(owner))
            it.remove(Keys.hiddenGenres(owner))
            it.remove(Keys.hiddenPodcasts(owner))
            it.remove(Keys.allowExplicitContent(owner))
            it.remove(Keys.defaultInboxMode(owner))
            it.remove(Keys.startScreen(owner))
            it.remove(Keys.visualizer(owner))
            it.remove(Keys.proxyImages(owner))
            it.remove(Keys.playbackSpeed(owner))
            it.remove(Keys.downloadWifiOnly(owner))
            it.remove(Keys.skipSilence(owner))
            it.remove(Keys.volumeBoost(owner))
            it.remove(Keys.autoDownloadCount(owner))
            it.remove(Keys.downloadRetention(owner))
            it.remove(Keys.downloadConcurrency(owner))
            it.remove(Keys.downloadBudgetMb(owner))
            it.remove(Keys.settingsUpdatedAt(owner))
            it.remove(Keys.settingsFieldUpdatedAt(owner))
            it.remove(Keys.foreignSettings(owner))
        }
    }

    suspend fun migrateLegacyForCurrentOwner() {
        dataStore.edit {
            val owner = owner()
            if (it[Keys.migrationComplete(owner)] == true) return@edit
            it.copyIfAbsent(Keys.themeMode(owner), Keys.LEGACY_THEME_MODE)
            it.copyIfAbsent(Keys.palette(owner), Keys.LEGACY_PALETTE)
            it.copyIfAbsent(Keys.languages(owner), Keys.LEGACY_LANGUAGES)
            it.copyIfAbsent(Keys.interests(owner), Keys.LEGACY_INTERESTS)
            it.copyIfAbsent(Keys.hiddenGenres(owner), Keys.LEGACY_HIDDEN_GENRES)
            it.copyIfAbsent(Keys.hiddenPodcasts(owner), Keys.LEGACY_HIDDEN_PODCASTS)
            it.copyIfAbsent(Keys.defaultInboxMode(owner), Keys.LEGACY_DEFAULT_INBOX_MODE)
            it.remove(Keys.LEGACY_CATEGORY)
            it.copyIfAbsent(Keys.proxyImages(owner), Keys.LEGACY_PROXY_IMAGES)
            it.copyIfAbsent(Keys.playbackSpeed(owner), Keys.LEGACY_PLAYBACK_SPEED)
            it.copyIfAbsent(Keys.downloadWifiOnly(owner), Keys.LEGACY_DOWNLOAD_WIFI_ONLY)
            it.copyIfAbsent(Keys.skipSilence(owner), Keys.LEGACY_SKIP_SILENCE)
            it.copyIfAbsent(Keys.volumeBoost(owner), Keys.LEGACY_VOLUME_BOOST)
            it.copyIfAbsent(Keys.autoDownloadCount(owner), Keys.LEGACY_AUTO_DOWNLOAD_COUNT)
            it.copyIfAbsent(Keys.downloadRetention(owner), Keys.LEGACY_DOWNLOAD_RETENTION)
            it.copyIfAbsent(Keys.downloadConcurrency(owner), Keys.LEGACY_DOWNLOAD_CONCURRENCY)
            it.copyIfAbsent(Keys.downloadBudgetMb(owner), Keys.LEGACY_DOWNLOAD_BUDGET_MB)
            it.copyIfAbsent(Keys.settingsUpdatedAt(owner), Keys.LEGACY_SETTINGS_UPDATED_AT)
            it[Keys.migrationComplete(owner)] = true
        }
    }

    suspend fun migrateGuestToAccount(ownerId: String) {
        if (ownerId.isBlank()) return
        dataStore.edit {
            if (it[Keys.GUEST_PREFS_MERGED] == true) return@edit
            it.copyIfAbsent(Keys.themeMode(ownerId), Keys.themeMode(null), removeSource = false)
            it.copyIfAbsent(Keys.palette(ownerId), Keys.palette(null), removeSource = false)
            it.copyIfAbsent(Keys.languages(ownerId), Keys.languages(null), removeSource = false)
            it.copyIfAbsent(Keys.interests(ownerId), Keys.interests(null), removeSource = false)
            it.copyIfAbsent(
                Keys.hiddenGenres(ownerId),
                Keys.hiddenGenres(null),
                removeSource = false,
            )
            it.copyIfAbsent(
                Keys.hiddenPodcasts(ownerId),
                Keys.hiddenPodcasts(null),
                removeSource = false,
            )
            it.copyIfAbsent(
                Keys.allowExplicitContent(ownerId),
                Keys.allowExplicitContent(null),
                removeSource = false,
            )
            it.copyIfAbsent(
                Keys.defaultInboxMode(ownerId),
                Keys.defaultInboxMode(null),
                removeSource = false,
            )
            it.copyIfAbsent(Keys.startScreen(ownerId), Keys.startScreen(null), removeSource = false)
            it.copyIfAbsent(Keys.visualizer(ownerId), Keys.visualizer(null), removeSource = false)
            it.copyIfAbsent(Keys.proxyImages(ownerId), Keys.proxyImages(null), removeSource = false)
            it.copyIfAbsent(Keys.playbackSpeed(ownerId), Keys.playbackSpeed(null), removeSource = false)
            it.copyIfAbsent(
                Keys.downloadWifiOnly(ownerId),
                Keys.downloadWifiOnly(null),
                removeSource = false,
            )
            it.copyIfAbsent(Keys.skipSilence(ownerId), Keys.skipSilence(null), removeSource = false)
            it.copyIfAbsent(Keys.volumeBoost(ownerId), Keys.volumeBoost(null), removeSource = false)
            it.copyIfAbsent(
                Keys.autoDownloadCount(ownerId),
                Keys.autoDownloadCount(null),
                removeSource = false,
            )
            it.copyIfAbsent(
                Keys.downloadRetention(ownerId),
                Keys.downloadRetention(null),
                removeSource = false,
            )
            it.copyIfAbsent(
                Keys.downloadConcurrency(ownerId),
                Keys.downloadConcurrency(null),
                removeSource = false,
            )
            it.copyIfAbsent(
                Keys.downloadBudgetMb(ownerId),
                Keys.downloadBudgetMb(null),
                removeSource = false,
            )
            it.copyIfAbsent(
                Keys.settingsUpdatedAt(ownerId),
                Keys.settingsUpdatedAt(null),
                removeSource = false,
            )
            it.copyIfAbsent(
                Keys.foreignSettings(ownerId),
                Keys.foreignSettings(null),
                removeSource = false,
            )
            it[Keys.GUEST_PREFS_MERGED] = true
        }
    }

    suspend fun migrateUserScope(userId: String, ownerId: String) {
        if (userId.isBlank() || ownerId.isBlank() || userId == ownerId) return
        dataStore.edit {
            it.copyIfAbsent(Keys.themeMode(ownerId), Keys.themeMode(userId))
            it.copyIfAbsent(Keys.palette(ownerId), Keys.palette(userId))
            it.copyIfAbsent(Keys.languages(ownerId), Keys.languages(userId))
            it.copyIfAbsent(Keys.interests(ownerId), Keys.interests(userId))
            it.copyIfAbsent(Keys.hiddenGenres(ownerId), Keys.hiddenGenres(userId))
            it.copyIfAbsent(Keys.hiddenPodcasts(ownerId), Keys.hiddenPodcasts(userId))
            it.copyIfAbsent(Keys.allowExplicitContent(ownerId), Keys.allowExplicitContent(userId))
            it.copyIfAbsent(Keys.defaultInboxMode(ownerId), Keys.defaultInboxMode(userId))
            it.copyIfAbsent(Keys.startScreen(ownerId), Keys.startScreen(userId))
            it.copyIfAbsent(Keys.visualizer(ownerId), Keys.visualizer(userId))
            it.copyIfAbsent(Keys.proxyImages(ownerId), Keys.proxyImages(userId))
            it.copyIfAbsent(Keys.playbackSpeed(ownerId), Keys.playbackSpeed(userId))
            it.copyIfAbsent(Keys.downloadWifiOnly(ownerId), Keys.downloadWifiOnly(userId))
            it.copyIfAbsent(Keys.skipSilence(ownerId), Keys.skipSilence(userId))
            it.copyIfAbsent(Keys.volumeBoost(ownerId), Keys.volumeBoost(userId))
            it.copyIfAbsent(Keys.autoDownloadCount(ownerId), Keys.autoDownloadCount(userId))
            it.copyIfAbsent(Keys.downloadRetention(ownerId), Keys.downloadRetention(userId))
            it.copyIfAbsent(Keys.downloadConcurrency(ownerId), Keys.downloadConcurrency(userId))
            it.copyIfAbsent(Keys.downloadBudgetMb(ownerId), Keys.downloadBudgetMb(userId))
            it.copyIfAbsent(Keys.settingsUpdatedAt(ownerId), Keys.settingsUpdatedAt(userId))
            it.copyIfAbsent(Keys.foreignSettings(ownerId), Keys.foreignSettings(userId))
        }
    }

    private fun owner(): String? =
        accountStore.account.value?.let { accountStore.activeOwnerId() }

    private fun Preferences.toUserPreferences(owner: String?) = UserPreferences(
        serverUrl = ServerUrl.sanitizeStored(
            this[Keys.SERVER_URL].orEmpty(),
            KoalaCastDefaults.SERVER_URL,
        ).value,
        onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
        themeMode = this[Keys.themeMode(owner)]
            ?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
        palette = PaletteId.fromId(this[Keys.palette(owner)]),
        languages = this[Keys.languages(owner)] ?: emptySet(),
        interests = this[Keys.interests(owner)] ?: emptySet(),
        hiddenGenres = this[Keys.hiddenGenres(owner)] ?: emptySet(),
        hiddenPodcasts = this[Keys.hiddenPodcasts(owner)].orEmpty()
            .mapNotNullTo(mutableSetOf(), ::decodeHiddenPodcast),
        allowExplicitContent = this[Keys.allowExplicitContent(owner)] ?: false,
        defaultInboxMode = when (this[Keys.defaultInboxMode(owner)]) {
            InboxMode.LATEST.name.lowercase() -> InboxMode.LATEST
            else -> InboxMode.ALL
        },
        startScreen = StartScreen.fromId(this[Keys.startScreen(owner)]),
        visualizer = VisualizerStyle.fromId(this[Keys.visualizer(owner)]),
        proxyImages = this[Keys.proxyImages(owner)] ?: true,
        playbackSpeed = this[Keys.playbackSpeed(owner)] ?: 1f,
        downloadWifiOnly = this[Keys.downloadWifiOnly(owner)] ?: true,
        skipSilence = this[Keys.skipSilence(owner)] ?: false,
        volumeBoost = this[Keys.volumeBoost(owner)] ?: false,
        autoDownloadCount = this[Keys.autoDownloadCount(owner)] ?: 3,
        downloadRetention = DownloadRetention.fromId(this[Keys.downloadRetention(owner)]),
        downloadConcurrency = (this[Keys.downloadConcurrency(owner)] ?: 2).coerceIn(1, 4),
        downloadBudgetBytes = (this[Keys.downloadBudgetMb(owner)] ?: 2_048).toLong() * MB,
        downloadStorage = DownloadStorage.fromId(this[Keys.DOWNLOAD_STORAGE]),
        downloadTreeUri = this[Keys.DOWNLOAD_TREE_URI].orEmpty(),
    )

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val INSECURE_SERVER_RESET_PENDING =
            booleanPreferencesKey("insecure_server_reset_pending")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private fun scoped(base: String, owner: String?) = "$base:${owner ?: "guest"}"
        fun themeMode(owner: String?) = stringPreferencesKey(scoped("theme_mode", owner))
        fun palette(owner: String?) = stringPreferencesKey(scoped("palette", owner))
        fun languages(owner: String?) = stringSetPreferencesKey(scoped("languages", owner))
        fun interests(owner: String?) = stringSetPreferencesKey(scoped("interests", owner))
        fun hiddenGenres(owner: String?) = stringSetPreferencesKey(scoped("hidden_genres", owner))
        fun hiddenPodcasts(owner: String?) = stringSetPreferencesKey(scoped("hidden_podcasts", owner))
        fun allowExplicitContent(owner: String?) =
            booleanPreferencesKey(scoped("allow_explicit_content", owner))
        fun defaultInboxMode(owner: String?) = stringPreferencesKey(scoped("default_inbox_mode", owner))
        fun startScreen(owner: String?) = stringPreferencesKey(scoped("start_screen", owner))
        fun visualizer(owner: String?) = stringPreferencesKey(scoped("visualizer", owner))
        fun proxyImages(owner: String?) = booleanPreferencesKey(scoped("proxy_images", owner))
        fun playbackSpeed(owner: String?) = floatPreferencesKey(scoped("playback_speed", owner))
        fun downloadWifiOnly(owner: String?) = booleanPreferencesKey(scoped("download_wifi_only", owner))
        fun skipSilence(owner: String?) = booleanPreferencesKey(scoped("skip_silence", owner))
        fun volumeBoost(owner: String?) = booleanPreferencesKey(scoped("volume_boost", owner))
        fun autoDownloadCount(owner: String?) = intPreferencesKey(scoped("auto_download_count", owner))
        fun downloadRetention(owner: String?) = stringPreferencesKey(scoped("download_retention", owner))
        fun downloadConcurrency(owner: String?) = intPreferencesKey(scoped("download_concurrency", owner))
        fun downloadBudgetMb(owner: String?) = intPreferencesKey(scoped("download_budget_mb", owner))
        val DOWNLOAD_STORAGE = stringPreferencesKey("download_storage")
        val DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
        fun settingsUpdatedAt(owner: String?) = longPreferencesKey(scoped("settings_updated_at", owner))
        fun foreignSettings(owner: String?) = stringPreferencesKey(scoped("settings_foreign", owner))
        fun settingsFieldUpdatedAt(owner: String?) =
            stringPreferencesKey(scoped("settings_field_updated_at", owner))
        fun migrationComplete(owner: String?) = booleanPreferencesKey(scoped("legacy_migrated", owner))
        val LEGACY_THEME_MODE = stringPreferencesKey("theme_mode")
        val LEGACY_PALETTE = stringPreferencesKey("palette")
        val LEGACY_LANGUAGES = stringSetPreferencesKey("languages")
        val LEGACY_INTERESTS = stringSetPreferencesKey("interests")
        val LEGACY_HIDDEN_GENRES = stringSetPreferencesKey("hidden_genres")
        val LEGACY_HIDDEN_PODCASTS = stringSetPreferencesKey("hidden_podcasts")
        val LEGACY_DEFAULT_INBOX_MODE = stringPreferencesKey("default_inbox_mode")
        val LEGACY_CATEGORY = stringPreferencesKey("category")
        val LEGACY_PROXY_IMAGES = booleanPreferencesKey("proxy_images")
        val LEGACY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val LEGACY_DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val LEGACY_SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val LEGACY_VOLUME_BOOST = booleanPreferencesKey("volume_boost")
        val LEGACY_AUTO_DOWNLOAD_COUNT = intPreferencesKey("auto_download_count")
        val LEGACY_DOWNLOAD_RETENTION = stringPreferencesKey("download_retention")
        val LEGACY_DOWNLOAD_CONCURRENCY = intPreferencesKey("download_concurrency")
        val LEGACY_DOWNLOAD_BUDGET_MB = intPreferencesKey("download_budget_mb")
        val LEGACY_SETTINGS_UPDATED_AT = longPreferencesKey("settings_updated_at")
        val GUEST_PREFS_MERGED = booleanPreferencesKey("guest_preferences_merged")
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.copyIfAbsent(
        target: Preferences.Key<T>,
        source: Preferences.Key<T>,
        removeSource: Boolean = true,
    ) {
        if (this[target] == null) this[source]?.let { this[target] = it }
        if (removeSource) remove(source)
    }

    /**
     * Marks the settings blob dirty, and records *which* fields changed.
     *
     * The per-field stamps let a merge keep both halves of two concurrent edits
     * instead of letting the newer blob win all of it; see [SyncedSettings.decide].
     * A caller that names no field still bumps the blob timestamp, which is the
     * older, coarser behaviour.
     */
    private fun androidx.datastore.preferences.core.MutablePreferences.touch(
        owner: String?,
        vararg fields: String,
    ) {
        val now = System.currentTimeMillis()
        this[Keys.settingsUpdatedAt(owner)] = now
        if (fields.isEmpty()) return
        val merged = SyncedSettings.parseTimestamps(
            this[Keys.settingsFieldUpdatedAt(owner)].orEmpty(),
        ) + fields.associateWith { now }
        this[Keys.settingsFieldUpdatedAt(owner)] = encodeFieldTimestamps(merged)
    }

    private fun encodeFieldTimestamps(values: Map<String, Long>): String =
        JsonObject(values.mapValues { (_, timestamp) -> JsonPrimitive(timestamp) }).toString()

    private fun encodeHiddenPodcast(podcast: HiddenPodcast): String =
        podcast.key + HIDDEN_PODCAST_SEPARATOR +
            podcast.title.replace(HIDDEN_PODCAST_SEPARATOR, " ")

    private fun decodeHiddenPodcast(value: String): HiddenPodcast? {
        val parts = value.split(HIDDEN_PODCAST_SEPARATOR, limit = 2)
        val key = parts.firstOrNull()?.trim().orEmpty()
        if (key.isBlank()) return null
        return HiddenPodcast(
            key = key,
            title = parts.getOrElse(1) { key }.trim().ifBlank { key },
        )
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val HIDDEN_PODCAST_SEPARATOR = "\u001F"
    }
}

object KoalaCastDefaults {
    /**
     * The instance the project runs. Self-hosters replace it in onboarding or in
     * Settings; nothing in the app assumes this particular origin.
     */
    const val SERVER_URL = "https://cast.koalastuff.net"

    /** Debug builds expose an emulator-loopback shortcut; release resolves to HTTPS. */
    val EMULATOR_LOOPBACK_URL: String
        get() = BuildVariantServerDefaults.emulatorLoopbackUrl
}

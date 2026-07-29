package net.koalastuff.koalacast.core.model

/** Mirrors the web client's theme choice; Android adds "follow the system". */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Everything the app knows before it has talked to anything. All of it lives in
 * DataStore on-device — there is no server-side profile.
 */
data class UserPreferences(
    val serverUrl: String,
    val onboardingComplete: Boolean,
    val themeMode: ThemeMode,
    /** Which of the nine colour palettes paints the app. */
    val palette: PaletteId,
    /** BCP-47-ish language codes ("en", "de") used to filter Discover and Search. */
    val languages: Set<String>,
    /** Genres explicitly preferred by the listener. */
    val interests: Set<String>,
    /** Genres vetoed by the listener and removed from Discover and Search. */
    val hiddenGenres: Set<String>,
    /** Individual shows removed from recommendation and search surfaces. */
    val hiddenPodcasts: Set<HiddenPodcast>,
    /** Inbox mode assigned to subscriptions created from now on. */
    val defaultInboxMode: InboxMode,
    /**
     * Route cover art through the configured KoalaCast server instead of fetching it
     * straight from the publisher's CDN. Costs a hop, hides the listener's IP.
     */
    val proxyImages: Boolean,
    /** Default playback speed; a per-show setting overrides it. */
    val playbackSpeed: Float,
    /** Avoid metered mobile data for large episode files by default. */
    val downloadWifiOnly: Boolean,
    /**
     * Drop the gaps between words. Speech-only content, so this is safe here in a
     * way it would not be for music.
     */
    val skipSilence: Boolean,
    /** Lift quiet recordings toward a consistent level. */
    val volumeBoost: Boolean,
    /** How many of the newest episodes auto-download keeps per opted-in show. */
    val autoDownloadCount: Int,
    /** When automatically downloaded episodes may be removed again. */
    val downloadRetention: DownloadRetention,
    /** Number of parallel download lanes. Each lane remains strictly ordered. */
    val downloadConcurrency: Int,
    /** Hard budget for completed episode files; zero means unlimited. */
    val downloadBudgetBytes: Long,
    /** App-private internal/external storage or a persisted Storage Access Framework tree. */
    val downloadStorage: DownloadStorage,
    val downloadTreeUri: String,
)

data class HiddenPodcast(
    val key: String,
    val title: String,
)

enum class DownloadStorage(val id: String) {
    INTERNAL("internal"),
    EXTERNAL("external"),
    SAF("saf"),
    ;

    companion object {
        fun fromId(value: String?): DownloadStorage =
            entries.firstOrNull { it.id == value } ?: INTERNAL
    }
}

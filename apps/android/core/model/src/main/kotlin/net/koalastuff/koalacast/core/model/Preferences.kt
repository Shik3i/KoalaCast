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
    /** BCP-47-ish language codes ("en", "de") used to filter Discover and Search. */
    val languages: Set<String>,
    /** iTunes genre used as the default Search filter; blank means "everything". */
    val category: String,
    /**
     * Route cover art through the configured KoalaCast server instead of fetching it
     * straight from the publisher's CDN. Costs a hop, hides the listener's IP.
     */
    val proxyImages: Boolean,
    /** Default playback speed; a per-show setting overrides it. */
    val playbackSpeed: Float,
)

package net.koalastuff.koalacast.core.data.repository

import kotlinx.serialization.json.JsonObject

/**
 * Both clients sync their preferences as a single `settings` entity, and the server
 * keeps only the newest write of the whole blob — it does not merge. Their key sets
 * do not overlap: Android owns the theme, palette, start screen and download
 * policy; the web client owns `date_format` and `ui_language`.
 *
 * A payload rebuilt purely from the fields *this* client understands therefore
 * deletes the other client's keys from the server on every push. Nothing breaks on
 * the device that pushed — both clients ignore keys they do not know and keep their
 * current value — so the damage only shows up on a fresh install, which restores
 * whichever half the last writer happened to know about.
 *
 * The fix is to treat unknown keys as somebody else's property: remember them when
 * a payload is applied, hand them back untouched when one is pushed.
 */
internal object SyncedSettings {

    /**
     * Every key this client writes. A key added to the payload must be added here
     * in the same change, or this client treats its own key as foreign and the
     * merge writes it twice.
     */
    val ownedKeys = setOf(
        "theme_mode",
        "palette",
        "languages",
        "interests",
        "hidden_genres",
        "hidden_podcasts",
        "default_inbox_mode",
        "start_screen",
        "proxy_images",
        "playback_speed",
        "download_wifi_only",
        "skip_silence",
        "volume_boost",
        "auto_download_count",
        "download_retention",
        "download_concurrency",
        "download_budget_bytes",
        "updated_at",
    )

    /** The part of an incoming payload that belongs to another client. */
    fun foreignOf(payload: JsonObject): JsonObject =
        JsonObject(payload.filterKeys { it !in ownedKeys })

    /**
     * This client's payload with the other client's keys restored. Owned keys win:
     * a client that wrongly claims one of ours must not be able to overwrite the
     * value we are pushing.
     */
    fun merge(owned: JsonObject, foreign: JsonObject): JsonObject =
        JsonObject(foreign.filterKeys { it !in ownedKeys } + owned)
}

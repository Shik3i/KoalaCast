package net.koalastuff.koalacast.core.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Both clients sync their preferences as a single `settings` entity, and the server
 * keeps only the newest write of the whole blob — it does not merge. Both clients
 * own the shared cross-device preferences; client-specific and future keys still
 * need to survive a write from a client that does not understand them yet.
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
        "visualizer",
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
        // The per-field timestamps this client maintains. Owned, so it is never
        // kept as somebody else's key and written back twice.
        FIELD_UPDATED_AT,
    )

    const val FIELD_UPDATED_AT = "field_updated_at"

    /**
     * The settings that carry a value. [ownedKeys] without the two bookkeeping
     * entries, which describe the payload rather than being part of it.
     */
    val mergeableFields: Set<String> =
        ownedKeys - setOf("updated_at", FIELD_UPDATED_AT)

    /**
     * Per-field conflict resolution for the settings blob.
     *
     * One `updated_at` for the whole object means the newest write wins all of
     * it, which is wrong for fields that are independent: change the interface
     * language on the phone at 10:00 and the palette in the browser at 10:01, and
     * the browser's payload — newer as a whole — silently reverts the language.
     * Nothing is reported; the setting just goes back.
     *
     * A payload without `field_updated_at` is read the old way, with every field
     * taking the blob timestamp, which is exactly what a client that predates
     * this change means. The same fallback applies to local state that has no
     * per-field history yet, so an upgrading installation does not look
     * untouched and lose everything to the first payload that arrives.
     */
    fun parseTimestamps(payload: JsonObject): Map<String, Long> {
        val node = payload[FIELD_UPDATED_AT] as? JsonObject ?: return emptyMap()
        return node.mapNotNull { (field, value) ->
            val timestamp = (value as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            if (timestamp > 0) field to timestamp else null
        }.toMap()
    }

    fun parseTimestamps(json: String): Map<String, Long> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                .mapNotNull { (field, value) ->
                    val timestamp = value.jsonPrimitive.longOrNull ?: return@mapNotNull null
                    if (timestamp > 0) field to timestamp else null
                }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** The fields whose incoming value wins, with the timestamp to record for each. */
    fun decide(
        incoming: Map<String, Long>,
        incomingUpdatedAt: Long,
        local: Map<String, Long>,
        localUpdatedAt: Long,
        authoritative: Boolean = false,
    ): Map<String, Long> = mergeableFields.mapNotNull { field ->
        val incomingAt = incoming[field] ?: incomingUpdatedAt
        val localAt = local[field] ?: localUpdatedAt
        if (authoritative || incomingAt > localAt) field to incomingAt else null
    }.toMap()

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

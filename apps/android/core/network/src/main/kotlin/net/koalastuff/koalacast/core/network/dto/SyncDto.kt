package net.koalastuff.koalacast.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncOperationDto(
    @SerialName("client_op_id") val clientOpId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("entity_type") val entityType: String,
    val action: String,
    @SerialName("entity_id") val entityId: String,
    val payload: JsonElement = JsonObject(emptyMap()),
    @SerialName("client_timestamp") val clientTimestamp: Long,
)

@Serializable
data class SyncPushRequest(
    val operations: List<SyncOperationDto>,
    @SerialName("client_schema_version") val clientSchemaVersion: Int = 2,
)

@Serializable
data class SyncPushResponse(
    @SerialName("applied_ops") val appliedOps: Int = 0,
    @SerialName("current_cursor") val currentCursor: Long = 0,
)

@Serializable
data class SyncChangesetDto(
    @SerialName("entity_type") val entityType: String = "",
    @SerialName("entity_id") val entityId: String = "",
    val action: String = "",
    val payload: JsonElement = JsonObject(emptyMap()),
    @SerialName("client_timestamp") val clientTimestamp: Long = 0,
    @SerialName("server_cursor") val serverCursor: Long = 0,
)

@Serializable
data class SyncPullResponse(
    @SerialName("since_cursor") val sinceCursor: Long = 0,
    @SerialName("next_cursor") val nextCursor: Long? = null,
    @SerialName("current_cursor") val currentCursor: Long = 0,
    @SerialName("has_more") val hasMore: Boolean? = null,
    val changesets: List<SyncChangesetDto> = emptyList(),
)

@Serializable
data class SyncSnapshotResponse(
    val cursor: Long = 0,
    val subscriptions: List<JsonObject> = emptyList(),
    val favorites: List<JsonObject> = emptyList(),
    @SerialName("playback_states") val playbackStates: List<JsonObject> = emptyList(),
    @SerialName("listening_sessions") val listeningSessions: List<JsonObject> = emptyList(),
    @SerialName("podcast_settings") val podcastSettings: List<JsonObject> = emptyList(),
)

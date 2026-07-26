package net.koalastuff.koalacast.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(val username: String, val password: String)

@Serializable
data class RegisterResponse(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val role: String = "",
    @SerialName("recovery_code") val recoveryCode: String = "",
    val warning: String = "",
)

@Serializable
data class DeviceLoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("client_type") val clientType: String = "android",
)

@Serializable
data class DeviceLoginResponse(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_token") val deviceToken: String = "",
)

@Serializable
data class RecoveryRequest(
    val username: String,
    @SerialName("recovery_code") val recoveryCode: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class AuthMessageResponse(
    val message: String = "",
    val error: String = "",
)

@Serializable
data class AuthStatusResponse(
    val authenticated: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val role: String = "",
    @SerialName("client_type") val clientType: String = "",
)

@Serializable
data class AccountSessionDto(
    val id: String = "",
    val kind: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("device_type") val deviceType: String = "",
    @SerialName("truncated_ip") val truncatedIp: String = "",
    @SerialName("sanitized_user_agent") val sanitizedUserAgent: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("last_used_at") val lastUsedAt: Long = 0,
    @SerialName("is_current") val isCurrent: Boolean = false,
)

@Serializable
data class SessionsResponse(val sessions: List<AccountSessionDto> = emptyList())

@Serializable
data class GlobalStatsPreference(
    @SerialName("global_stats_opt_in") val enabled: Boolean = false,
)

@Serializable
data class OpmlImportedPodcast(
    val id: String = "",
    val title: String = "",
    @SerialName("feed_url") val feedUrl: String = "",
    @SerialName("artwork_url") val artworkUrl: String = "",
)

@Serializable
data class OpmlImportFailure(val url: String = "", val reason: String = "")

@Serializable
data class OpmlImportReport(
    @SerialName("total_found") val totalFound: Int = 0,
    val imported: Int = 0,
    val skipped: Int = 0,
    val failures: List<OpmlImportFailure> = emptyList(),
    val podcasts: List<OpmlImportedPodcast> = emptyList(),
)

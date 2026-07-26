package net.koalastuff.koalacast.core.model

data class Account(
    val userId: String,
    val username: String,
    val role: String = "user",
    val deviceId: String,
)

data class AccountSession(
    val id: String,
    val kind: String,
    val deviceName: String,
    val deviceType: String,
    val truncatedIp: String,
    val userAgent: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val isCurrent: Boolean,
)

enum class SyncStatus { OFF, IDLE, SYNCING, ERROR }

data class OpmlReport(
    val totalFound: Int,
    val imported: Int,
    val skipped: Int,
    val failures: List<String>,
)

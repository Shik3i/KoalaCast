package net.koalastuff.koalacast.core.data.repository

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.koalastuff.koalacast.core.data.db.ContentCacheDao
import net.koalastuff.koalacast.core.data.db.ContentCacheEntity
import net.koalastuff.koalacast.core.data.util.Clock
import javax.inject.Inject
import javax.inject.Singleton

data class CachedContent<T>(
    val value: T,
    val storedAt: Long,
) {
    fun isFresh(now: Long, ttlMs: Long): Boolean = now - storedAt < ttlMs
}

@Singleton
class ContentCache @Inject constructor(
    private val dao: ContentCacheDao,
    private val json: Json,
    private val clock: Clock,
) {
    suspend fun <T> get(key: String, serializer: KSerializer<T>): CachedContent<T>? {
        val row = dao.get(key) ?: return null
        return runCatching {
            CachedContent(json.decodeFromString(serializer, row.payloadJson), row.storedAt)
        }.getOrNull()
    }

    suspend fun <T> put(key: String, value: T, serializer: KSerializer<T>) {
        dao.upsert(
            ContentCacheEntity(
                cacheKey = key,
                payloadJson = json.encodeToString(serializer, value),
                storedAt = clock.nowMs(),
            ),
        )
    }

    fun now(): Long = clock.nowMs()
}

object ContentTtl {
    const val DISCOVER = 4 * 60 * 60_000L
    const val SEARCH = 30 * 60_000L
    const val INBOX = 5 * 60_000L
    const val PODCAST = 5 * 60 * 60_000L
    const val EPISODE_LIST = 10 * 60_000L
    const val EPISODE = 24 * 60 * 60_000L
    const val AUXILIARY = 24 * 60 * 60_000L
}

package net.koalastuff.koalacast.core.data.repository

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.db.NamedQueueDao
import net.koalastuff.koalacast.core.data.db.NamedQueueEntity
import net.koalastuff.koalacast.core.data.db.QueueDao
import net.koalastuff.koalacast.core.data.db.QueueItemEntity
import net.koalastuff.koalacast.core.data.util.Clock
import net.koalastuff.koalacast.core.model.NamedQueue
import net.koalastuff.koalacast.core.data.prefs.PreferencesRepository
import net.koalastuff.koalacast.core.model.isAllowedByExplicitPreference

@Singleton
class NamedQueueRepository @Inject constructor(
    private val namedQueues: NamedQueueDao,
    private val queue: QueueDao,
    private val json: Json,
    private val clock: Clock,
    private val syncMetadata: SecureAccountStore,
    private val preferences: PreferencesRepository? = null,
) {
    val all: Flow<List<NamedQueue>> = namedQueues.observeAll().map { items ->
        items.map { NamedQueue(it.id, it.name, it.itemCount, it.updatedAt) }
    }

    suspend fun save(name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return
        val items = queue.getAll()
        if (items.isEmpty()) return
        val existing = namedQueues.getByName(normalizedName)
        namedQueues.upsert(
            NamedQueueEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = normalizedName,
                itemsJson = json.encodeToString(ListSerializer(QueueItemEntity.serializer()), items),
                itemCount = items.size,
                updatedAt = clock.nowMs(),
            ),
        )
    }

    suspend fun restore(id: String) {
        val saved = namedQueues.get(id) ?: return
        val items = json.decodeFromString(
            ListSerializer(QueueItemEntity.serializer()),
            saved.itemsJson,
        )
        val includeExplicit = preferences?.preferences?.first()?.allowExplicitContent ?: false
        queue.clear()
        items.filter { it.explicit.isAllowedByExplicitPreference(includeExplicit) }
            .take(MAX_QUEUE_ITEMS).forEachIndexed { index, item ->
            queue.insert(
                item.copy(
                    id = UUID.randomUUID().toString(),
                    positionOrder = index.toLong(),
                    addedAt = clock.nowMs() + index,
                ),
            )
        }
        syncMetadata.markQueueUpdated(clock.nowMs())
    }

    suspend fun delete(id: String) {
        namedQueues.delete(id)
    }

    private companion object {
        const val MAX_QUEUE_ITEMS = 500
    }
}

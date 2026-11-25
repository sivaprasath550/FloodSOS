package com.floodsos.data

import com.floodsos.core.SosMessage
import com.floodsos.data.local.SosDao
import com.floodsos.data.local.toDomain
import com.floodsos.data.local.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only class the UI talks to for messages. Later phases add upload (Phase 2),
 * SMS (Phase 3) and relay (Phases 4-5) behind it, and the UI won't need to change.
 */
class SosRepository(private val dao: SosDao) {

    fun observeMessages(): Flow<List<SosMessage>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** @return true if saved, false if a message with this id was already stored. */
    suspend fun save(message: SosMessage): Boolean = dao.insert(message.toEntity()) != -1L

    suspend fun pendingUploads(): List<SosMessage> = dao.pendingUploads().map { it.toDomain() }

    suspend fun markUploaded(id: String) = dao.markUploaded(id)

    suspend fun get(id: String): SosMessage? = dao.getById(id)?.toDomain()

    suspend fun markSmsSent(id: String) = dao.markSmsSent(id)

    suspend fun all(): List<SosMessage> = dao.getAll().map { it.toDomain() }

    suspend fun count(): Int = dao.count()

    suspend fun countNotUploaded(): Int = dao.countNotUploaded()

    suspend fun existingIds(ids: List<String>): Set<String> =
        if (ids.isEmpty()) emptySet() else dao.existingIds(ids).toSet()
}

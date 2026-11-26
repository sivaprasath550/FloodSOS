package com.floodsos.data

import com.floodsos.core.ExchangeRecord
import com.floodsos.core.HeldMessage
import com.floodsos.core.SosMessage
import com.floodsos.data.local.SosDao
import com.floodsos.data.local.toDomain
import com.floodsos.data.local.toEntity
import com.floodsos.data.local.toHeld
import com.floodsos.data.local.toRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only class the rest of the app talks to for stored messages. Upload,
 * SMS and relay all go through it, so the UI never touches Room directly.
 */
class SosRepository(
    private val dao: SosDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeMessages(): Flow<List<SosMessage>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** With arrival/upload times, for the Stats screen. */
    fun observeHeld(): Flow<List<HeldMessage>> =
        dao.observeAll().map { rows -> rows.map { it.toHeld() } }

    /** @return true if saved, false if a message with this id was already stored. */
    suspend fun save(message: SosMessage): Boolean = dao.insert(message.toEntity(receivedAt = clock())) != -1L

    suspend fun pendingUploads(): List<SosMessage> = dao.pendingUploads().map { it.toDomain() }

    suspend fun markUploaded(id: String) = dao.markUploaded(id, clock())

    suspend fun get(id: String): SosMessage? = dao.getById(id)?.toDomain()

    suspend fun markSmsSent(id: String) = dao.markSmsSent(id)

    suspend fun all(): List<SosMessage> = dao.getAll().map { it.toDomain() }

    suspend fun allHeld(): List<HeldMessage> = dao.getAll().map { it.toHeld() }

    suspend fun count(): Int = dao.count()

    suspend fun countNotUploaded(): Int = dao.countNotUploaded()

    suspend fun existingIds(ids: List<String>): Set<String> =
        if (ids.isEmpty()) emptySet() else dao.existingIds(ids).toSet()

    // ---- field-test exchange log ----

    suspend fun recordExchange(record: ExchangeRecord) = dao.insertExchange(record.toEntity())

    fun observeExchanges(): Flow<List<ExchangeRecord>> =
        dao.observeExchanges().map { rows -> rows.map { it.toRecord() } }

    suspend fun allExchanges(): List<ExchangeRecord> = dao.getExchanges().map { it.toRecord() }
}

package com.floodsos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.floodsos.core.ExchangeRecord

/** One Bluetooth meeting, logged for field-test analysis (added in DB v3). */
@Entity(tableName = "exchanges")
data class ExchangeEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val peer: String,
    val startedAt: Long,
    val durationMs: Long,
    val sent: Int,
    val receivedNew: Int,
    val success: Boolean,
)

fun ExchangeEntity.toRecord() = ExchangeRecord(peer, startedAt, durationMs, sent, receivedNew, success)

fun ExchangeRecord.toEntity() =
    ExchangeEntity(peer = peer, startedAt = startedAt, durationMs = durationMs, sent = sent, receivedNew = receivedNew, success = success)

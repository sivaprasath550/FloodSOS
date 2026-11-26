package com.floodsos.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything one phone knows, as a JSON file for tools/analyze.py.
 *
 * Why phones must export at all: Firestore only shows what ARRIVED. To compute
 * a delivery rate we also need what was SENT, and only the sending phones know
 * that, including messages that never made it anywhere.
 *
 * Contains locations and notes: treat exports as private field-test data.
 */
@Serializable
data class FieldExport(
    val format: Int = FORMAT,
    val deviceId: String,
    val exportedAt: Long,
    val appVersion: String,
    val messages: List<ExportedMessage>,
    val exchanges: List<ExportedExchange>,
) {
    companion object {
        const val FORMAT = 1
    }
}

@Serializable
data class ExportedMessage(
    val id: String,
    val senderId: String,
    val type: String,
    val lat: Double,
    val lng: Double,
    val note: String,
    val createdAt: Long,
    val hops: Int,
    val uploaded: Boolean,
    val smsSent: Boolean,
    val receivedAt: Long,
    val uploadedAt: Long?,
)

@Serializable
data class ExportedExchange(
    val peer: String,
    val startedAt: Long,
    val durationMs: Long,
    val sent: Int,
    val receivedNew: Int,
    val success: Boolean,
)

object FieldExportCodec {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun build(
        deviceId: String,
        now: Long,
        appVersion: String,
        held: List<HeldMessage>,
        exchanges: List<ExchangeRecord>,
    ) = FieldExport(
        deviceId = deviceId,
        exportedAt = now,
        appVersion = appVersion,
        messages = held.sortedBy { it.message.createdAt }.map { h ->
            val m = h.message
            ExportedMessage(
                m.id, m.senderId, m.type, m.lat, m.lng, m.note, m.createdAt, m.hops,
                m.uploaded, m.smsSent, h.receivedAt, h.uploadedAt,
            )
        },
        exchanges = exchanges.sortedBy { it.startedAt }.map {
            ExportedExchange(it.peer, it.startedAt, it.durationMs, it.sent, it.receivedNew, it.success)
        },
    )

    fun toJson(export: FieldExport): String = json.encodeToString(FieldExport.serializer(), export)

    fun fromJson(text: String): FieldExport = json.decodeFromString(FieldExport.serializer(), text)
}

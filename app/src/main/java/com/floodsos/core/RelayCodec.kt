package com.floodsos.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A message as it travels between phones. No local-only fields (uploaded, smsSent). */
@Serializable
data class WireMessage(
    val id: String,
    val senderId: String,
    val type: String,
    val lat: Double,
    val lng: Double,
    val note: String,
    val createdAt: Long,
    val hops: Int,
)

/**
 * One Nearby BYTES payload. A batch can need several packets; `last = true` on the
 * final one tells the peer "I'm done sending".
 */
@Serializable
data class RelayPacket(
    @SerialName("v") val version: Int = RelayCodec.VERSION,
    val last: Boolean,
    val messages: List<WireMessage>,
)

object RelayCodec {
    /**
     * Bump this if the format ever changes incompatibly. Phones in the field can't
     * all be updated at once, so old and new versions will meet.
     */
    const val VERSION = 1

    /** Nearby caps a BYTES payload at 32 KB; leave some headroom. */
    const val MAX_PACKET_BYTES = 30_000

    private val json = Json {
        ignoreUnknownKeys = true // a newer app may add fields: old apps still read the rest
        encodeDefaults = true
    }

    /** Splits [messages] into packets that each fit in [maxBytes]. Always at least one. */
    fun encode(messages: List<SosMessage>, maxBytes: Int = MAX_PACKET_BYTES): List<ByteArray> {
        val overhead = packetSize(emptyList())
        val chunks = mutableListOf<MutableList<WireMessage>>(mutableListOf())
        var size = overhead

        for (m in messages) {
            val wire = m.toWire()
            val itemSize = json.encodeToString(WireMessage.serializer(), wire).toByteArray().size + 1 // + comma
            if (chunks.last().isNotEmpty() && size + itemSize > maxBytes) {
                chunks.add(mutableListOf())
                size = overhead
            }
            chunks.last().add(wire)
            size += itemSize
        }

        return chunks.mapIndexed { i, chunk ->
            val packet = RelayPacket(last = i == chunks.lastIndex, messages = chunk)
            json.encodeToString(RelayPacket.serializer(), packet).toByteArray()
        }
    }

    /** @return null for anything we can't read (garbage, or a newer incompatible version). */
    fun decode(bytes: ByteArray): RelayPacket? = try {
        json.decodeFromString(RelayPacket.serializer(), bytes.decodeToString())
            .takeIf { it.version == VERSION }
    } catch (e: IllegalArgumentException) { // SerializationException is a subclass
        null
    }

    fun WireMessage.toDomain() = SosMessage(id, senderId, type, lat, lng, note, createdAt, hops, uploaded = false)

    private fun SosMessage.toWire() = WireMessage(id, senderId, type, lat, lng, note, createdAt, hops)

    private fun packetSize(messages: List<WireMessage>) =
        json.encodeToString(RelayPacket.serializer(), RelayPacket(last = true, messages = messages))
            .toByteArray().size
}

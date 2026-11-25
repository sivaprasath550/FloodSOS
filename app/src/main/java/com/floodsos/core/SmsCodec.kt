package com.floodsos.core

import java.nio.ByteBuffer
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * Turns a message into one SMS and back:
 *
 *     TYPE|lat|lng|note|id|sender|time
 *     SOS|13.08270|80.27070|3 people, 2nd floor|9Xq...22 chars|Ab3...22 chars|lz8k2m1c
 *
 * - lat/lng: 5 decimals (~1.1 m), always with '.', whatever the phone's language
 * - id/sender: UUIDs packed from 36 chars to 22 (16 raw bytes in base64url)
 * - time: createdAt millis in base 36 (13 digits -> 8 chars)
 *
 * The worst case is 147 GSM-7 characters, so it fits in one 160-character SMS.
 * ('|' is in GSM-7's extension table and costs 2 characters each.)
 */
object SmsCodec {
    private const val SEP = "|"
    private const val FIELD_COUNT = 7

    fun encode(m: SosMessage): String = listOf(
        m.type,
        "%.5f".format(Locale.US, m.lat), // Locale.US: in e.g. German, %.5f gives "13,08270"
        "%.5f".format(Locale.US, m.lng),
        SosMessage.sanitizeNote(m.note),
        packUuid(m.id),
        packUuid(m.senderId),
        m.createdAt.toString(36),
    ).joinToString(SEP)

    /**
     * @return the message, or null if this SMS isn't a valid FloodSOS message.
     * Anyone can text the gateway, so every field is checked.
     */
    fun decode(text: String): SosMessage? {
        val p = text.trim().split(SEP)
        if (p.size != FIELD_COUNT) return null
        return try {
            val createdAt = p[6].toLong(36)
            if (createdAt <= 0) return null
            SosMessage.create(            // re-validates type and coordinates, re-sanitizes note
                type = p[0],
                lat = p[1].toDouble(),    // "NaN"/"Infinity" parse, but fail the range check
                lng = p[2].toDouble(),
                note = p[3],
                id = unpackUuid(p[4]),    // must rebuild the EXACT original id: it's the dedup key
                senderId = unpackUuid(p[5]),
                now = createdAt,
            )
        } catch (e: IllegalArgumentException) { // includes NumberFormatException
            null
        }
    }

    internal fun packUuid(uuid: String): String {
        val u = UUID.fromString(uuid)
        val bytes = ByteBuffer.allocate(16)
            .putLong(u.mostSignificantBits)
            .putLong(u.leastSignificantBits)
            .array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    internal fun unpackUuid(packed: String): String {
        require(packed.length == 22) { "Bad packed UUID: $packed" }
        val buf = ByteBuffer.wrap(Base64.getUrlDecoder().decode(packed))
        return UUID(buf.long, buf.long).toString()
    }
}

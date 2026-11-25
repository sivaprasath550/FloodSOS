package com.floodsos.core

import java.util.UUID

/**
 * The one message type that travels through every channel (internet, SMS, Bluetooth).
 *
 * Pure Kotlin: no Android imports allowed in the `core` package, so all of it
 * can be unit-tested on a plain JVM in milliseconds.
 */
data class SosMessage(
    val id: String,        // UUID: dedup key, and the Firestore document ID (idempotent upload)
    val senderId: String,  // random per-install device id
    val type: String,      // MessageType.SOS or MessageType.SAFE
    val lat: Double,
    val lng: Double,
    val note: String,      // e.g. "3 people, 2nd floor"
    val createdAt: Long,   // epoch millis, set on the sender's phone
    val hops: Int,         // 0 on the sender's phone, +1 per relay
    val uploaded: Boolean, // local only, never sent to other phones
    val smsSent: Boolean = false, // local only: SMS fallback already handed to the radio
) {
    companion object {
        /** Keeps the note small enough to fit in one SMS later (Phase 3). */
        const val MAX_NOTE_LENGTH = 60

        /**
         * Builds a new message created on this phone. Time and id come in as
         * parameters, so tests can pass fixed values instead of the real clock.
         */
        fun create(
            senderId: String,
            type: String,
            lat: Double,
            lng: Double,
            note: String,
            now: Long,
            id: String = UUID.randomUUID().toString(),
        ): SosMessage {
            require(type == MessageType.SOS || type == MessageType.SAFE) { "Unknown type: $type" }
            require(lat in -90.0..90.0 && lng in -180.0..180.0) { "Invalid coordinates: $lat,$lng" }
            return SosMessage(
                id = id,
                senderId = senderId,
                type = type,
                lat = lat,
                lng = lng,
                note = sanitizeNote(note),
                createdAt = now,
                hops = 0,
                uploaded = false,
            )
        }

        /**
         * '|' is the field separator in our SMS format (SOS|lat|lng|note|id),
         * so a note containing '|' would break parsing. Newlines are also removed.
         */
        fun sanitizeNote(raw: String): String =
            raw.replace(Regex("[|\\r\\n]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_NOTE_LENGTH)
    }
}

object MessageType {
    const val SOS = "SOS"
    const val SAFE = "SAFE"
}

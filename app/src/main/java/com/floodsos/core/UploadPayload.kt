package com.floodsos.core

/** How a message reached the server. The dashboard and the Phase 6 stats use this. */
object Channel {
    const val INTERNET = "internet" // uploaded by the phone that created it
    const val RELAY = "relay"       // carried by other phones over Bluetooth, then uploaded
    const val SMS = "sms"           // written by the SMS gateway (Phase 3)
}

/**
 * Works out the channel without storing extra state:
 *  - hops > 0: other phones carried it (relay always adds a hop)
 *  - hops == 0, uploaded by someone else: only the SMS gateway does that
 *  - hops == 0, uploaded by its own sender: direct internet
 */
fun channelFor(m: SosMessage, uploaderId: String): String = when {
    m.hops > 0 -> Channel.RELAY
    m.senderId != uploaderId -> Channel.SMS
    else -> Channel.INTERNET
}

/**
 * The fields stored in Firestore for one message (except `receivedAt`, which is
 * a server timestamp and gets added by the Firestore layer).
 *
 * `uploaded` and `smsSent` are not included: they're local bookkeeping.
 */
fun SosMessage.toUploadFields(uploaderId: String): Map<String, Any> = mapOf(
    "id" to id,
    "senderId" to senderId,
    "type" to type,
    "lat" to lat,
    "lng" to lng,
    "note" to note,
    "createdAt" to createdAt,
    "hops" to hops,
    "channel" to channelFor(this, uploaderId),
    "uploaderId" to uploaderId,
)

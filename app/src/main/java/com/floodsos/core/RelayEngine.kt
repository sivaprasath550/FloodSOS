package com.floodsos.core

/**
 * The store-and-forward rules, with no Android, Bluetooth or database code.
 * The Nearby layer asks it three questions:
 *   1. Who should open the connection?   -> [shouldInitiate]
 *   2. What do I send to a phone I meet? -> [selectToSend]
 *   3. What do I keep from what I got?   -> [acceptIncoming]
 *
 * This is "epidemic routing": every phone hands everything to everyone, and the
 * hop limit stops messages spreading forever. It's simple and robust when nobody
 * knows where the rescuers' internet will appear. The cost is extra copies.
 */
class RelayEngine(private val maxHops: Int = MAX_HOPS) {

    /**
     * Everything that may still travel (hops < maxHops). The order matters because a
     * Bluetooth link can drop halfway: SOS before SAFE, and the longest-waiting first.
     */
    fun selectToSend(held: List<SosMessage>): List<SosMessage> =
        held.filter { it.hops < maxHops }
            .sortedWith(compareBy({ it.type != MessageType.SOS }, { it.createdAt }))

    /**
     * @param received what the peer sent (untrusted: it could be a buggy or malicious app)
     * @param knownIds ids already in our database
     * @return new rows to store: hops + 1, local flags reset
     */
    fun acceptIncoming(received: List<SosMessage>, knownIds: Set<String>): List<SosMessage> {
        val seenInBatch = HashSet<String>()
        return received
            .filter { isValid(it) }
            .filter { it.hops < maxHops }        // a well-behaved peer never sends these
            .filter { it.id !in knownIds }       // dedup against what we hold
            .filter { seenInBatch.add(it.id) }   // dedup inside the batch itself
            .map {
                it.copy(
                    hops = it.hops + 1,
                    note = SosMessage.sanitizeNote(it.note),
                    uploaded = false, // OUR copy isn't uploaded yet, whatever the peer did
                    smsSent = false,
                )
            }
    }

    /**
     * Both phones discover each other at about the same time. If both called
     * requestConnection, the requests could collide. A rule both sides compute the
     * same way breaks the tie: the phone with the smaller name asks.
     */
    fun shouldInitiate(myName: String, peerName: String): Boolean = myName < peerName

    private fun isValid(m: SosMessage): Boolean =
        m.id.length in 1..MAX_ID_LENGTH &&
            m.senderId.length in 1..MAX_ID_LENGTH &&
            (m.type == MessageType.SOS || m.type == MessageType.SAFE) &&
            m.lat in -90.0..90.0 && m.lng in -180.0..180.0 && // NaN fails these too
            m.createdAt > 0 &&
            m.hops >= 0

    companion object {
        const val MAX_HOPS = 5
        private const val MAX_ID_LENGTH = 64 // same limit as the Firestore rules
    }
}

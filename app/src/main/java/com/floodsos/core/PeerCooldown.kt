package com.floodsos.core

/**
 * Two phones lying side by side would otherwise re-discover each other every
 * duty cycle and re-send everything each minute. After a successful exchange we
 * skip that peer for [cooldownMs]. Creating a new SOS clears all cooldowns, because
 * now we have something new to share.
 */
class PeerCooldown(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    private val lastSync = HashMap<String, Long>()

    fun canSync(peer: String, now: Long): Boolean =
        lastSync[peer]?.let { now - it >= cooldownMs } ?: true

    fun markSynced(peer: String, now: Long) {
        lastSync[peer] = now
    }

    fun clear() = lastSync.clear()

    companion object {
        const val DEFAULT_COOLDOWN_MS = 120_000L
    }
}

package com.floodsos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCooldownTest {

    private val cooldown = PeerCooldown(cooldownMs = 120_000)

    @Test
    fun `unknown peer can sync`() {
        assertTrue(cooldown.canSync("peer", now = 0))
    }

    @Test
    fun `peer is skipped during cooldown and allowed after`() {
        cooldown.markSynced("peer", now = 1_000)
        assertFalse(cooldown.canSync("peer", now = 60_000))
        assertTrue(cooldown.canSync("peer", now = 121_000))
    }

    @Test
    fun `cooldown is per peer`() {
        cooldown.markSynced("a", now = 0)
        assertTrue(cooldown.canSync("b", now = 1))
    }

    @Test
    fun `clear allows immediate re-sync`() {
        cooldown.markSynced("a", now = 0)
        cooldown.clear()
        assertTrue(cooldown.canSync("a", now = 1))
    }
}

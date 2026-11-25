package com.floodsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEngineTest {

    private val engine = RelayEngine(maxHops = 5)

    private fun msg(
        id: String,
        hops: Int = 0,
        type: String = MessageType.SOS,
        createdAt: Long = 1_000L,
        lat: Double = 13.08,
        uploaded: Boolean = false,
    ) = SosMessage(id, "sender-$id", type, lat, 80.27, "note", createdAt, hops, uploaded)

    // ---- selectToSend ----

    @Test
    fun `sends only messages below the hop limit`() {
        val held = listOf(msg("a", hops = 0), msg("b", hops = 4), msg("c", hops = 5), msg("d", hops = 9))
        assertEquals(listOf("a", "b"), engine.selectToSend(held).map { it.id })
    }

    @Test
    fun `SOS goes before SAFE, oldest first`() {
        val held = listOf(
            msg("safe-old", type = MessageType.SAFE, createdAt = 1),
            msg("sos-new", createdAt = 300),
            msg("sos-old", createdAt = 100),
        )
        assertEquals(listOf("sos-old", "sos-new", "safe-old"), engine.selectToSend(held).map { it.id })
    }

    // ---- acceptIncoming ----

    @Test
    fun `new message is stored with one more hop`() {
        val stored = engine.acceptIncoming(listOf(msg("a", hops = 2)), knownIds = emptySet())
        assertEquals(1, stored.size)
        assertEquals(3, stored[0].hops)
    }

    @Test
    fun `message we already hold is ignored (dedup)`() {
        val stored = engine.acceptIncoming(listOf(msg("a"), msg("b")), knownIds = setOf("a"))
        assertEquals(listOf("b"), stored.map { it.id })
    }

    @Test
    fun `duplicates inside one batch are stored once`() {
        val stored = engine.acceptIncoming(listOf(msg("a", hops = 1), msg("a", hops = 3)), emptySet())
        assertEquals(1, stored.size)
        assertEquals(2, stored[0].hops) // first copy wins
    }

    @Test
    fun `message at the hop limit is dropped`() {
        assertTrue(engine.acceptIncoming(listOf(msg("a", hops = 5)), emptySet()).isEmpty())
    }

    @Test
    fun `message received at hops 4 is stored at 5 and then not forwarded again`() {
        val stored = engine.acceptIncoming(listOf(msg("a", hops = 4)), emptySet())
        assertEquals(5, stored.single().hops)
        assertTrue(engine.selectToSend(stored).isEmpty())
    }

    @Test
    fun `our copy is never marked uploaded just because the peer uploaded it`() {
        val stored = engine.acceptIncoming(listOf(msg("a", uploaded = true)), emptySet())
        assertFalse(stored.single().uploaded)
    }

    @Test
    fun `invalid messages from a buggy or malicious peer are rejected`() {
        val bad = listOf(
            msg("", hops = 0),                         // empty id
            msg("x".repeat(65)),                      // id too long
            msg("lat", lat = 200.0),
            msg("nan", lat = Double.NaN),
            msg("type", type = "HACK"),
            msg("neg", hops = -1),
            msg("time", createdAt = 0),
        )
        assertTrue(engine.acceptIncoming(bad, emptySet()).isEmpty())
    }

    @Test
    fun `note from a peer is sanitized`() {
        val dirty = msg("a").copy(note = "a|b\n" + "x".repeat(100))
        val note = engine.acceptIncoming(listOf(dirty), emptySet()).single().note
        assertFalse(note.contains('|'))
        assertTrue(note.length <= SosMessage.MAX_NOTE_LENGTH)
    }

    // ---- shouldInitiate ----

    @Test
    fun `exactly one of two phones initiates`() {
        assertTrue(engine.shouldInitiate("aaaa", "bbbb"))
        assertFalse(engine.shouldInitiate("bbbb", "aaaa"))
    }

    // ---- multi-hop chain: P0 -> P1 -> ... -> P7 ----

    @Test
    fun `hop limit stops a message after 5 relays`() {
        val phones = List(8) { mutableListOf<SosMessage>() }
        phones[0] += msg("sos-1")
        for (i in 0 until phones.lastIndex) {
            val from = phones[i]
            val to = phones[i + 1]
            to += engine.acceptIncoming(engine.selectToSend(from), to.map { it.id }.toSet())
        }
        // P1..P5 hold it with hops 1..5; P5 doesn't forward (hops == limit), so P6, P7 get nothing.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), phones.take(6).map { it.single().hops })
        assertTrue(phones[6].isEmpty())
        assertTrue(phones[7].isEmpty())
    }

    // ---- a small 3-phone simulation: A -> B -> C ----

    @Test
    fun `message travels two hops and dedups when it comes back`() {
        val a = mutableListOf(msg("sos-1"))
        val b = mutableListOf<SosMessage>()
        val c = mutableListOf<SosMessage>()

        fun meet(from: MutableList<SosMessage>, to: MutableList<SosMessage>) {
            to += engine.acceptIncoming(engine.selectToSend(from), to.map { it.id }.toSet())
        }

        meet(a, b)
        meet(b, c)
        meet(c, a) // echoes back to the sender: must not be duplicated

        assertEquals(1, b.single().hops)
        assertEquals(2, c.single().hops)
        assertEquals(1, a.size)
    }
}

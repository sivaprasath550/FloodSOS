package com.floodsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SosMessageTest {

    private fun create(note: String = "", type: String = MessageType.SOS, lat: Double = 13.08, lng: Double = 80.27) =
        SosMessage.create(senderId = "dev-1", type = type, lat = lat, lng = lng, note = note, now = 1_000L, id = "msg-1")

    @Test
    fun `new message starts with zero hops and not uploaded`() {
        val m = create(note = "3 people, 2nd floor")
        assertEquals(0, m.hops)
        assertFalse(m.uploaded)
        assertEquals("msg-1", m.id)
        assertEquals(1_000L, m.createdAt)
        assertEquals("3 people, 2nd floor", m.note)
    }

    @Test
    fun `pipe and newlines are removed so the SMS format cannot break`() {
        assertEquals("a b c", SosMessage.sanitizeNote("a|b\nc"))
        assertEquals("x y", SosMessage.sanitizeNote("  x ||| \r\n y  "))
    }

    @Test
    fun `note is capped at max length`() {
        val m = create(note = "x".repeat(200))
        assertEquals(SosMessage.MAX_NOTE_LENGTH, m.note.length)
    }

    @Test
    fun `tamil text is kept`() {
        assertEquals("மூன்று பேர்", SosMessage.sanitizeNote("மூன்று பேர்"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown type is rejected`() {
        create(type = "HELLO")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid coordinates are rejected`() {
        create(lat = 200.0)
    }
}

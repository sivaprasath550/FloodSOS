package com.floodsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.UUID

class SmsCodecTest {

    private fun message(
        note: String = "3 people, 2nd floor",
        type: String = MessageType.SOS,
        lat: Double = 13.0827012,
        lng: Double = 80.2707034,
    ) = SosMessage.create(
        senderId = UUID.randomUUID().toString(),
        type = type, lat = lat, lng = lng, note = note,
        now = 1_732_270_000_123L,
        id = UUID.randomUUID().toString(),
    )

    @Test
    fun `round trip keeps every field (coordinates to 5 decimals)`() {
        val original = message()
        val decoded = SmsCodec.decode(SmsCodec.encode(original))!!
        assertEquals(original.id, decoded.id) // exact: this is the dedup key across channels
        assertEquals(original.senderId, decoded.senderId)
        assertEquals(original.type, decoded.type)
        assertEquals(original.note, decoded.note)
        assertEquals(original.createdAt, decoded.createdAt)
        assertEquals(13.08270, decoded.lat, 1e-9)
        assertEquals(80.27070, decoded.lng, 1e-9)
        assertEquals(0, decoded.hops)
    }

    @Test
    fun `worst case fits in a single 160 char GSM-7 SMS`() {
        val worst = message(
            type = MessageType.SAFE, lat = -89.99999, lng = -179.99999,
            note = "x".repeat(SosMessage.MAX_NOTE_LENGTH),
        )
        val text = SmsCodec.encode(worst)
        // '|' is in the GSM-7 extension table: it costs 2 septets.
        val septets = text.length + text.count { it == '|' }
        assertTrue("was $septets", septets <= 160)
    }

    @Test
    fun `decimal point is used even when the phone language uses a comma`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val text = SmsCodec.encode(message())
            assertTrue(text, text.contains("|13.08270|80.27070|"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `tamil note survives the round trip`() {
        val decoded = SmsCodec.decode(SmsCodec.encode(message(note = "மூன்று பேர், 2வது மாடி")))!!
        assertEquals("மூன்று பேர், 2வது மாடி", decoded.note)
    }

    @Test
    fun `uuid packing is reversible`() {
        val id = UUID.randomUUID().toString()
        val packed = SmsCodec.packUuid(id)
        assertEquals(22, packed.length)
        assertEquals(id, SmsCodec.unpackUuid(packed))
    }

    @Test
    fun `trailing whitespace from carriers is ignored`() {
        val original = message()
        assertEquals(original.id, SmsCodec.decode(SmsCodec.encode(original) + "\n ")!!.id)
    }

    @Test
    fun `garbage and tampered messages are rejected`() {
        val good = SmsCodec.encode(message()).split("|")
        fun with(index: Int, value: String) = good.toMutableList().also { it[index] = value }.joinToString("|")

        assertNull(SmsCodec.decode("Hi, are you safe?"))
        assertNull(SmsCodec.decode(""))
        assertNull(SmsCodec.decode(good.dropLast(1).joinToString("|"))) // missing field
        assertNull(SmsCodec.decode(with(0, "HELLO")))                    // unknown type
        assertNull(SmsCodec.decode(with(1, "200.0")))                    // latitude out of range
        assertNull(SmsCodec.decode(with(1, "NaN")))
        assertNull(SmsCodec.decode(with(2, "abc")))
        assertNull(SmsCodec.decode(with(4, "not-a-uuid")))
        assertNull(SmsCodec.decode(with(6, "-5")))                       // negative time
    }
}

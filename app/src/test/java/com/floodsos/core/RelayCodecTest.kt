package com.floodsos.core

import com.floodsos.core.RelayCodec.toDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCodecTest {

    private fun msg(i: Int) = SosMessage(
        id = "00000000-0000-0000-0000-%012d".format(i),
        senderId = "sender-$i", type = MessageType.SOS,
        lat = 13.0827012, lng = 80.2707034, note = "note $i மூன்று", createdAt = 1_000L + i,
        hops = i % 5, uploaded = true, smsSent = true,
    )

    @Test
    fun `round trip keeps full precision and every travelling field`() {
        val original = msg(1)
        val packet = RelayCodec.decode(RelayCodec.encode(listOf(original)).single())!!
        val back = packet.messages.single().toDomain()
        assertEquals(original.copy(uploaded = false, smsSent = false), back)
        assertTrue(packet.last)
    }

    @Test
    fun `local-only flags are not sent over the air`() {
        val text = RelayCodec.encode(listOf(msg(1))).single().decodeToString()
        assertFalse(text.contains("uploaded"))
        assertFalse(text.contains("smsSent"))
    }

    @Test
    fun `empty batch is still one packet marked last`() {
        val packets = RelayCodec.encode(emptyList())
        assertEquals(1, packets.size)
        val p = RelayCodec.decode(packets.single())!!
        assertTrue(p.last)
        assertTrue(p.messages.isEmpty())
    }

    @Test
    fun `large batch is split under the size limit, only the final packet is last`() {
        val messages = (1..1000).map(::msg)
        val packets = RelayCodec.encode(messages)

        assertTrue(packets.size > 1)
        packets.forEach { assertTrue("${it.size} bytes", it.size <= RelayCodec.MAX_PACKET_BYTES) }

        val decoded = packets.map { RelayCodec.decode(it)!! }
        assertTrue(decoded.last().last)
        assertTrue(decoded.dropLast(1).none { it.last })
        assertEquals(messages.map { it.id }, decoded.flatMap { it.messages }.map { it.id }) // order kept
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(RelayCodec.decode("hello".toByteArray()))
        assertNull(RelayCodec.decode(byteArrayOf(0, 1, 2, -1)))
        assertNull(RelayCodec.decode("""{"v":1}""".toByteArray()))
    }

    @Test
    fun `unknown future fields are ignored but a different version is rejected`() {
        val newerFields = """{"v":1,"last":true,"compression":"none","messages":[]}"""
        assertTrue(RelayCodec.decode(newerFields.toByteArray())!!.last)

        val v2 = """{"v":2,"last":true,"messages":[]}"""
        assertNull(RelayCodec.decode(v2.toByteArray()))
    }
}

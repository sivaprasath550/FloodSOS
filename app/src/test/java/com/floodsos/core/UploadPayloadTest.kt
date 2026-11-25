package com.floodsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UploadPayloadTest {

    private val original = SosMessage.create(
        senderId = "dev-A", type = MessageType.SOS, lat = 13.08, lng = 80.27,
        note = "2 people", now = 1_000L, id = "msg-1",
    )

    @Test
    fun `own message is tagged internet`() {
        val fields = original.toUploadFields(uploaderId = "dev-A")
        assertEquals(Channel.INTERNET, fields["channel"])
        assertEquals("msg-1", fields["id"])
        assertEquals("dev-A", fields["uploaderId"])
    }

    @Test
    fun `relayed message is tagged relay and keeps the original sender`() {
        val relayed = original.copy(hops = 2)
        val fields = relayed.toUploadFields(uploaderId = "dev-C")
        assertEquals(Channel.RELAY, fields["channel"])
        assertEquals("dev-A", fields["senderId"])
        assertEquals("dev-C", fields["uploaderId"])
        assertEquals(2, fields["hops"])
    }

    @Test
    fun `message uploaded by the gateway with zero hops is tagged sms`() {
        val fields = original.toUploadFields(uploaderId = "gateway-phone")
        assertEquals(Channel.SMS, fields["channel"])
        assertEquals("dev-A", fields["senderId"])
    }

    @Test
    fun `local-only flags are never sent`() {
        val fields = original.copy(uploaded = true, smsSent = true).toUploadFields("dev-A")
        assertFalse(fields.containsKey("uploaded"))
        assertFalse(fields.containsKey("smsSent"))
    }

    @Test
    fun `field names match the Firestore security rules`() {
        // If you rename a field here, update firestore.rules too (hasOnly/hasAll).
        assertEquals(
            setOf("id", "senderId", "type", "lat", "lng", "note", "createdAt", "hops", "channel", "uploaderId"),
            original.toUploadFields("x").keys,
        )
    }
}

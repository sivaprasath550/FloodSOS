package com.floodsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldStatsTest {

    private val me = "me"

    private fun held(
        id: String,
        sender: String = me,
        type: String = MessageType.SOS,
        hops: Int = 0,
        createdAt: Long = 1_000,
        receivedAt: Long = 1_000,
        uploadedAt: Long? = null,
        smsSent: Boolean = false,
    ) = HeldMessage(
        SosMessage(id, sender, type, 13.0, 80.0, "", createdAt, hops, uploaded = uploadedAt != null, smsSent = smsSent),
        receivedAt, uploadedAt,
    )

    // ---- Percentiles ----

    @Test
    fun `nearest rank picks real observed values`() {
        val v = listOf(10L, 20, 30, 40, 50, 60, 70, 80, 90, 100)
        assertEquals(50L, Percentiles.nearestRank(v, 50.0))
        assertEquals(90L, Percentiles.nearestRank(v, 90.0))
        assertEquals(100L, Percentiles.nearestRank(v, 100.0))
        assertEquals(10L, Percentiles.nearestRank(v, 1.0))
    }

    @Test
    fun `single value is every percentile`() {
        assertEquals(7L, Percentiles.nearestRank(listOf(7L), 50.0))
        assertEquals(7L, Percentiles.nearestRank(listOf(7L), 90.0))
    }

    @Test
    fun `empty summary has no numbers`() {
        val s = DurationSummary.of(emptyList())
        assertEquals(0, s.count)
        assertNull(s.medianMs)
    }

    // ---- FieldStats ----

    @Test
    fun `own messages are counted by type and upload state`() {
        val stats = FieldStats.compute(
            listOf(
                held("a", uploadedAt = 4_000),
                held("b", type = MessageType.SAFE, smsSent = true),
                held("c"),
            ),
            emptyList(), me,
        )
        assertEquals(2, stats.ownSos)
        assertEquals(1, stats.ownSafe)
        assertEquals(1, stats.ownUploadedHere)
        assertEquals(2, stats.ownWaiting)
        assertEquals(1, stats.ownSmsSent)
        assertEquals(3_000L, stats.ownUploadDelay.medianMs) // 4000 - 1000
    }

    @Test
    fun `messages from others are split into relay and sms`() {
        val stats = FieldStats.compute(
            listOf(
                held("r1", sender = "x", hops = 1, receivedAt = 10_000, uploadedAt = 70_000),
                held("r2", sender = "y", hops = 3),
                held("s1", sender = "z", hops = 0, receivedAt = 5_000, uploadedAt = 6_000),
            ),
            emptyList(), me,
        )
        assertEquals(2, stats.othersViaRelay)
        assertEquals(1, stats.othersViaSms)
        assertEquals(2, stats.othersUploadedHere)
        assertEquals(2, stats.carryTime.count)
        assertEquals(60_000L, stats.carryTime.maxMs)
    }

    @Test
    fun `rows from before the v3 migration are ignored for carry time`() {
        val stats = FieldStats.compute(
            listOf(held("old", sender = "x", hops = 1, receivedAt = 0, uploadedAt = 9_000)),
            emptyList(), me,
        )
        assertEquals(0, stats.carryTime.count)
    }

    @Test
    fun `hop distribution covers everything held`() {
        val stats = FieldStats.compute(
            listOf(held("a"), held("b", sender = "x", hops = 1), held("c", sender = "y", hops = 1), held("d", sender = "z", hops = 4)),
            emptyList(), me,
        )
        assertEquals(mapOf(0 to 1, 1 to 2, 4 to 1), stats.hopCounts)
        assertEquals(listOf(0, 1, 4), stats.hopCounts.keys.toList()) // sorted
    }

    @Test
    fun `exchange stats count peers once and time only successful exchanges`() {
        val ex = listOf(
            ExchangeRecord("p1", 0, 4_000, sent = 2, receivedNew = 1, success = true),
            ExchangeRecord("p1", 200_000, 6_000, sent = 2, receivedNew = 0, success = true),
            ExchangeRecord("p2", 300_000, 30_000, sent = 1, receivedNew = 0, success = false),
        )
        val stats = FieldStats.compute(emptyList(), ex, me)
        assertEquals(3, stats.exchanges)
        assertEquals(2, stats.exchangesOk)
        assertEquals(2, stats.uniquePeers)
        assertEquals(1, stats.receivedViaRelay)
        assertEquals(6_000L, stats.exchangeDuration.maxMs) // the 30 s failure isn't counted
    }

    // ---- Export ----

    @Test
    fun `export round trips through json`() {
        val export = FieldExportCodec.build(
            deviceId = me, now = 99, appVersion = "0.1.0",
            held = listOf(held("b", createdAt = 2_000), held("a", createdAt = 1_000, uploadedAt = 1_500)),
            exchanges = listOf(ExchangeRecord("p", 5, 6, 1, 2, true)),
        )
        val back = FieldExportCodec.fromJson(FieldExportCodec.toJson(export))
        assertEquals(export, back)
        assertEquals(listOf("a", "b"), back.messages.map { it.id }) // sorted by time
        assertEquals(1_500L, back.messages.first().uploadedAt)
        assertEquals(FieldExport.FORMAT, back.format)
    }
}

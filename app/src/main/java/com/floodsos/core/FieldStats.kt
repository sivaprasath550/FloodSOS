package com.floodsos.core

import kotlin.math.ceil

/** A message as stored on this phone, plus when it arrived here and when we uploaded it. */
data class HeldMessage(
    val message: SosMessage,
    val receivedAt: Long,  // when THIS phone stored it (0 = unknown, rows from before v3)
    val uploadedAt: Long?, // when THIS phone got the server's confirmation (null = not by us)
)

/** One Bluetooth meeting with another phone. */
data class ExchangeRecord(
    val peer: String,
    val startedAt: Long,
    val durationMs: Long,
    val sent: Int,
    val receivedNew: Int,
    val success: Boolean,
)

data class DurationSummary(val count: Int, val medianMs: Long?, val p90Ms: Long?, val maxMs: Long?) {
    companion object {
        fun of(values: List<Long>): DurationSummary {
            if (values.isEmpty()) return DurationSummary(0, null, null, null)
            val sorted = values.sorted()
            return DurationSummary(
                count = sorted.size,
                medianMs = Percentiles.nearestRank(sorted, 50.0),
                p90Ms = Percentiles.nearestRank(sorted, 90.0),
                maxMs = sorted.last(),
            )
        }
    }
}

object Percentiles {
    /**
     * Nearest-rank percentile: always returns a real observed value, never an
     * interpolated one. Same method as tools/analyze.py, so the app and the report agree.
     */
    fun nearestRank(sorted: List<Long>, p: Double): Long {
        require(sorted.isNotEmpty()) { "No values" }
        require(p > 0 && p <= 100) { "p must be in (0, 100]" }
        val rank = ceil(p / 100.0 * sorted.size).toInt()
        return sorted[rank - 1]
    }
}

/**
 * What the in-app Stats screen shows. This is one phone's view only. The
 * system-wide truth (did it reach the server, how fast) comes from
 * tools/analyze.py, which merges every phone's export with Firestore.
 */
data class FieldStats(
    val ownSos: Int,
    val ownSafe: Int,
    val ownUploadedHere: Int,
    val ownWaiting: Int,
    val ownSmsSent: Int,
    val ownUploadDelay: DurationSummary,  // createdAt -> uploadedAt, own messages we uploaded
    val othersViaRelay: Int,              // carried for other phones (hops > 0)
    val othersViaSms: Int,                // received as SMS gateway (hops == 0, someone else's)
    val othersUploadedHere: Int,
    val carryTime: DurationSummary,       // receivedAt -> uploadedAt, others' messages
    val hopCounts: Map<Int, Int>,         // hops -> number of messages held, sorted
    val exchanges: Int,
    val exchangesOk: Int,
    val uniquePeers: Int,
    val exchangeDuration: DurationSummary,
    val receivedViaRelay: Int,
) {
    companion object {
        fun compute(held: List<HeldMessage>, exchanges: List<ExchangeRecord>, myId: String): FieldStats {
            val (own, others) = held.partition { it.message.senderId == myId }

            val ownDelays = own.mapNotNull { h -> h.uploadedAt?.let { it - h.message.createdAt } }
                .filter { it >= 0 }
            val carry = others.mapNotNull { h ->
                if (h.receivedAt <= 0) null else h.uploadedAt?.let { it - h.receivedAt }
            }.filter { it >= 0 }
            val ok = exchanges.filter { it.success }

            return FieldStats(
                ownSos = own.count { it.message.type == MessageType.SOS },
                ownSafe = own.count { it.message.type == MessageType.SAFE },
                ownUploadedHere = own.count { it.uploadedAt != null },
                ownWaiting = own.count { !it.message.uploaded },
                ownSmsSent = own.count { it.message.smsSent },
                ownUploadDelay = DurationSummary.of(ownDelays),
                othersViaRelay = others.count { it.message.hops > 0 },
                othersViaSms = others.count { it.message.hops == 0 },
                othersUploadedHere = others.count { it.uploadedAt != null },
                carryTime = DurationSummary.of(carry),
                hopCounts = held.groupingBy { it.message.hops }.eachCount().toSortedMap(),
                exchanges = exchanges.size,
                exchangesOk = ok.size,
                uniquePeers = exchanges.map { it.peer }.toSet().size,
                exchangeDuration = DurationSummary.of(ok.map { it.durationMs }),
                receivedViaRelay = exchanges.sumOf { it.receivedNew },
            )
        }
    }
}

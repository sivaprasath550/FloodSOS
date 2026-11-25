package com.floodsos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DutyCycleTest {

    private val noJitter = 0.0

    @Test
    fun `sleep time depends on battery`() {
        val r = Random(1)
        assertEquals(DutyCycle.NORMAL_OFF_MS, DutyCycle.windowFor(80, false, r, noJitter).offMs)
        assertEquals(DutyCycle.NORMAL_OFF_MS, DutyCycle.windowFor(20, false, r, noJitter).offMs)
        assertEquals(DutyCycle.LOW_OFF_MS, DutyCycle.windowFor(19, false, r, noJitter).offMs)
        assertEquals(DutyCycle.CRITICAL_OFF_MS, DutyCycle.windowFor(9, false, r, noJitter).offMs)
        assertEquals(DutyCycle.CHARGING_OFF_MS, DutyCycle.windowFor(5, true, r, noJitter).offMs)
    }

    @Test
    fun `scan window is always 10 seconds`() {
        assertEquals(10_000L, DutyCycle.windowFor(50, false, Random(1)).onMs)
    }

    @Test
    fun `jitter stays within plus or minus 20 percent`() {
        val r = Random(42)
        repeat(10_000) {
            val off = DutyCycle.windowFor(80, false, r).offMs
            assertTrue("$off", off in 40_000L..60_000L)
        }
    }

    // ---- Why jitter matters: simulate two phones for 2 hours ----

    /** Scan intervals [start, end) for one phone over [horizonMs]. */
    private fun scanIntervals(startOffsetMs: Long, random: Random, jitter: Double, horizonMs: Long): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var t = startOffsetMs
        while (t < horizonMs) {
            val w = DutyCycle.windowFor(80, false, random, jitter)
            out += t until t + w.onMs
            t += w.onMs + w.offMs
        }
        return out
    }

    /** Phones "meet" if both scan at the same time for at least 3 s (time to discover + connect). */
    private fun meet(a: List<LongRange>, b: List<LongRange>): Boolean = a.any { x ->
        b.any { y -> minOf(x.last, y.last) - maxOf(x.first, y.first) >= 3_000 }
    }

    private val twoHours = 2 * 60 * 60 * 1000L

    @Test
    fun `without jitter two phones 30 s out of phase never meet`() {
        val a = scanIntervals(0, Random(1), noJitter, twoHours)
        val b = scanIntervals(30_000, Random(2), noJitter, twoHours)
        assertFalse(meet(a, b))
    }

    @Test
    fun `with jitter the same two phones meet`() {
        // Several seeds, so the test isn't passing by luck.
        for (seed in 1..20) {
            val a = scanIntervals(0, Random(seed), DutyCycle.DEFAULT_JITTER, twoHours)
            val b = scanIntervals(30_000, Random(seed + 1000), DutyCycle.DEFAULT_JITTER, twoHours)
            assertTrue("seed $seed", meet(a, b))
        }
    }
}

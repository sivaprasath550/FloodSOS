package com.floodsos.core

import kotlin.random.Random

/** One duty cycle: scan for [onMs], then sleep the radio for [offMs]. */
data class ScanWindow(val onMs: Long, val offMs: Long)

/**
 * How long to scan and how long to sleep, based on battery.
 *
 * Why jitter: two phones with exactly the same period keep the same phase offset
 * forever. If phone B's scan always starts 30 s after A's, their 10 s windows
 * NEVER overlap and they never meet. A random +/-20 % on the sleep time makes
 * the phases drift, so any two phones eventually overlap (see DutyCycleTest).
 */
object DutyCycle {
    const val ON_MS = 10_000L
    const val CHARGING_OFF_MS = 20_000L   // plugged in: battery isn't the constraint
    const val NORMAL_OFF_MS = 50_000L     // ~17 % radio duty
    const val LOW_OFF_MS = 170_000L       // < 20 %: ~6 % duty
    const val CRITICAL_OFF_MS = 290_000L  // < 10 %: ~3 %, keep the phone alive for its own SOS
    const val LOW_BATTERY = 20
    const val CRITICAL_BATTERY = 10
    const val DEFAULT_JITTER = 0.2

    fun windowFor(
        batteryPercent: Int,
        charging: Boolean,
        random: Random,
        jitter: Double = DEFAULT_JITTER,
    ): ScanWindow {
        val baseOff = when {
            charging -> CHARGING_OFF_MS
            batteryPercent < CRITICAL_BATTERY -> CRITICAL_OFF_MS
            batteryPercent < LOW_BATTERY -> LOW_OFF_MS
            else -> NORMAL_OFF_MS
        }
        val factor = 1.0 + jitter * (random.nextDouble() * 2 - 1) // in [1-jitter, 1+jitter)
        return ScanWindow(ON_MS, (baseOff * factor).toLong())
    }
}

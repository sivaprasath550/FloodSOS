package com.floodsos

import android.content.Context
import android.os.BatteryManager

data class BatteryStatus(val percent: Int, val charging: Boolean)

object Battery {
    fun read(context: Context): BatteryStatus {
        val bm = context.getSystemService(BatteryManager::class.java)
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // Some devices return Integer.MIN_VALUE when unknown: assume "normal".
        val percent = if (raw in 0..100) raw else 50
        return BatteryStatus(percent, bm.isCharging)
    }
}

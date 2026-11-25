package com.floodsos.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions Nearby Connections needs. They differ by Android version:
 *
 * - Android 11 and below: BLUETOOTH / BLUETOOTH_ADMIN are granted at install. Scanning
 *   for Bluetooth devices counts as location access, so FINE location is needed.
 * - Android 12+: the "Nearby devices" group: SCAN (find phones), ADVERTISE (be found),
 *   CONNECT (talk to them). We don't declare SCAN as "neverForLocation", because
 *   Nearby may need to read beacon-like BLE ads, so FINE location is still needed.
 * - Android 13+: NEARBY_WIFI_DEVICES, so Nearby can upgrade to Wi-Fi Direct for speed.
 */
object NearbyPermissions {
    fun required(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    fun allGranted(context: Context) = required().all { granted(context, it) }

    /** Android 13+: needed to SHOW the relay notification (optional for the relay to work). */
    fun notification(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    fun notificationsGranted(context: Context) = notification().all { granted(context, it) }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

package com.floodsos

import android.content.Context
import java.util.UUID

/**
 * A random id created on first launch and stored on the phone.
 * Not IMEI (needs privileged permission) and not ANDROID_ID (a hardware-linked
 * identifier we don't need). It identifies the sender, not the person.
 */
object DeviceId {
    private const val PREFS = "floodsos_prefs"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}

package com.floodsos

import android.content.Context

/**
 * Two small user settings. SharedPreferences is fine for this; DataStore would
 * be the modern choice if we needed to observe changes or store more.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("floodsos_settings", Context.MODE_PRIVATE)

    /** Phone number of the gateway phone that SOS SMS are sent to. Empty = SMS fallback off. */
    var gatewayNumber: String
        get() = prefs.getString(KEY_GATEWAY_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GATEWAY_NUMBER, value).apply()

    /** True on the gateway phone: incoming SOS SMS get uploaded to Firestore. */
    var gatewayMode: Boolean
        get() = prefs.getBoolean(KEY_GATEWAY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_GATEWAY_MODE, value).apply()

    private companion object {
        const val KEY_GATEWAY_NUMBER = "gateway_number"
        const val KEY_GATEWAY_MODE = "gateway_mode"
    }
}

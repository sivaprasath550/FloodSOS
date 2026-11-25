package com.floodsos.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class LocationFix(val lat: Double, val lng: Double, val accuracyMeters: Float)

class LocationProvider(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Asks for a fresh fix and gives up after [timeoutMs]; then falls back to the
     * last known location. Returns null if Location is switched off or no fix exists.
     *
     * GPS works without internet. Without data the first fix can be slow
     * (no A-GPS assistance), which is why we wait up to 20 s.
     */
    @SuppressLint("MissingPermission") // The UI checks the permission before calling this.
    suspend fun currentLocation(timeoutMs: Long = 20_000): LocationFix? {
        val cancel = CancellationTokenSource()
        try {
            val fresh = withTimeoutOrNull(timeoutMs) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token).await()
            }
            val loc = fresh ?: client.lastLocation.await() ?: return null
            return LocationFix(loc.latitude, loc.longitude, loc.accuracy)
        } finally {
            cancel.cancel() // stop the GPS request if we timed out, to save battery
        }
    }
}

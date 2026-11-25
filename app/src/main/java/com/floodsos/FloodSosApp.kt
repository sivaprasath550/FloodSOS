package com.floodsos

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.floodsos.core.SosMessage
import com.floodsos.data.SosRepository
import com.floodsos.data.local.AppDatabase
import com.floodsos.data.remote.FirestoreUploader
import com.floodsos.location.LocationProvider
import com.floodsos.nearby.NearbyPermissions
import com.floodsos.nearby.NearbyRelay
import com.floodsos.relay.RelayService
import com.floodsos.sms.SmsFallbackWorker
import com.floodsos.upload.UploadWorker
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency injection: the Application object owns one instance of each
 * shared service. `by lazy` creates each one the first time it's used.
 * (Hilt does the same with code generation. Not worth it for ~5 objects.)
 */
class FloodSosApp : Application() {
    val database by lazy { AppDatabase.build(this) }
    val repository by lazy { SosRepository(database.sosDao()) }
    val locationProvider by lazy { LocationProvider(this) }
    val deviceId by lazy { DeviceId.get(this) }
    val settings by lazy { AppSettings(this) }
    val uploader by lazy { FirestoreUploader(FirebaseFirestore.getInstance(), deviceId) }

    /** Endpoint name = first 8 chars of the device id: short (it's broadcast over BLE) and unique enough. */
    val nearby by lazy { NearbyRelay(this, repository, deviceId.take(8), ::requestUpload) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun requestUpload() = UploadWorker.enqueue(this)

    /**
     * The fallback ladder for a message created on THIS phone:
     *  1. internet upload, tried right away
     *  2. SMS to the gateway: immediately if there's no internet, otherwise after
     *     [SMS_ESCALATION_DELAY_S] in case the "working" internet is actually stuck.
     *  3. Bluetooth relay: no internet -> make sure the relay is running, and share
     *     with nearby phones right away (even ones we synced with recently).
     * All three may deliver; that's safe because the message id makes it idempotent.
     *
     * Called from the UI, so starting the foreground service here is allowed.
     * @return whether validated internet was available (for the status text).
     */
    fun deliver(message: SosMessage): Boolean {
        requestUpload()
        val online = Connectivity.hasInternet(this)
        SmsFallbackWorker.enqueue(this, message.id, if (online) SMS_ESCALATION_DELAY_S else 0)
        nearby.resetCooldowns()
        if (!online && NearbyPermissions.allGranted(this)) setRelayEnabled(true)
        return online
    }

    fun setRelayEnabled(enabled: Boolean) {
        if (enabled) RelayService.start(this) else RelayService.stop(this)
    }

    override fun onCreate() {
        super.onCreate()
        RelayService.createChannel(this)
        watchForInternet()
        // Catch anything left over from a previous session (e.g. saved while offline).
        requestUpload()
    }

    /**
     * Event-driven "auto-upload when internet returns": the moment Android confirms a
     * network really reaches the internet (VALIDATED), upload immediately instead of
     * waiting for a retry that may be far away. Works while the process is alive,
     * which the relay service guarantees.
     */
    private fun watchForInternet() {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            @Volatile private var validated = false

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (now && !validated) {
                    Log.d(TAG, "Internet is back: uploading now")
                    appScope.launch { UploadWorker.kickNow(this@FloodSosApp) }
                }
                validated = now
            }

            override fun onLost(network: Network) {
                validated = false
            }
        })
    }

    private companion object {
        const val TAG = "FloodSosApp"
        const val SMS_ESCALATION_DELAY_S = 30L
    }
}

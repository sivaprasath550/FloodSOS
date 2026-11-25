package com.floodsos.relay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.floodsos.Battery
import com.floodsos.Connectivity
import com.floodsos.FloodSosApp
import com.floodsos.MainActivity
import com.floodsos.R
import com.floodsos.core.DutyCycle
import com.floodsos.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Keeps the relay alive when the app is in the background or the screen is off.
 *
 * Why a foreground service: Android kills or freezes background apps to save
 * battery. A foreground service with a visible notification is the official way
 * to say "the user knows this is running, and wants it". Type connectedDevice
 * is the right category for Bluetooth/Nearby work (required since Android 14).
 *
 * Why not WorkManager: periodic work runs at most every 15 minutes, and its
 * timing is decided by the system. We need ~1-minute scan cycles.
 */
class RelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val app get() = application as FloodSosApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // e.g. started from the background (not allowed on Android 12+), or the
            // Nearby permissions were revoked (required for this type on Android 14+).
            Log.e(TAG, "Couldn't start the foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) {
            app.nearby.activate()
            loop = scope.launch { dutyCycleLoop() }
        }
        return START_STICKY // if Android kills us under memory pressure, restart when possible
    }

    private suspend fun dutyCycleLoop() {
        val random = Random.Default
        while (scope.isActive) {
            keepCpuAwake()
            val battery = Battery.read(this)
            val window = DutyCycle.windowFor(battery.percent, battery.charging, random)

            // Safety net for "auto-upload when internet returns". The main trigger is the
            // network callback in FloodSosApp; this catches anything it missed.
            if (Connectivity.hasInternet(this) && app.repository.countNotUploaded() > 0) {
                UploadWorker.kickNow(this)
            }

            val plug = if (battery.charging) " (charging)" else ""
            app.nearby.setCycleInfo(
                "Battery ${battery.percent}%$plug · scan ${window.onMs / 1000}s, sleep ${window.offMs / 1000}s"
            )
            app.nearby.startScanning()
            updateNotification()
            delay(window.onMs)

            app.nearby.stopScanning()
            updateNotification()
            delay(window.offMs)
        }
    }

    /**
     * Without this, the CPU deep-sleeps when the screen is off and `delay()` just
     * stops. The duty cycle saves the expensive part (the radio); an awake but idle
     * CPU costs much less. The timeout means a crash can't leave it held forever.
     */
    private fun keepCpuAwake() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FloodSOS:relay")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS) // refreshed every cycle
    }

    private suspend fun updateNotification() {
        val held = app.repository.count()
        val waiting = app.repository.countNotUploaded()
        val peers = app.nearby.state.value.connectedPeers
        val phase = if (app.nearby.state.value.scanning) "Scanning" else "Sleeping"
        val text = "$phase · carrying $held messages, $waiting not uploaded · $peers connected"
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_relay)
            .setContentTitle("FloodSOS relay is on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true) // updates every cycle shouldn't buzz
            .setContentIntent(open)
            .addAction(0, "Stop relay", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        app.nearby.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RelayService"
        private const val ACTION_STOP = "com.floodsos.STOP_RELAY"
        private const val CHANNEL_ID = "relay"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /** Must be called while the app is in the foreground (Android 12+ rule). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+): app went to the
                // background, e.g. during the GPS wait. The user can turn it on from the app.
                Log.w(TAG, "Can't start relay from the background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }

        /** LOW importance: shows in the shade, no sound, no pop-up. */
        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Relay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while FloodSOS relays messages between nearby phones"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

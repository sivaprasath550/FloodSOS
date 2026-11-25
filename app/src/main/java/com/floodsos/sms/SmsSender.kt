package com.floodsos.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

class SmsSender(private val context: Context) {

    private val smsManager: SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }

    /**
     * Sends [text] and suspends until the radio reports the result.
     *
     * @return true if every SMS part left the phone. Note that "sent" means the
     * cell network accepted it, not that the gateway received it (that would need
     * a delivery report or a reply SMS from the gateway).
     */
    suspend fun send(number: String, text: String, timeoutMs: Long = 60_000): Boolean {
        // divideMessage: 1 part for ASCII notes. A Tamil note switches the SMS to
        // UCS-2 (70 chars per part), so it becomes a multipart SMS that the
        // receiving phone reassembles.
        val parts = smsManager.divideMessage(text)
        val action = "com.floodsos.SMS_SENT.${UUID.randomUUID()}" // unique per send

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var remaining = parts.size
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        when {
                            resultCode != Activity.RESULT_OK -> finish(false) // e.g. NO_SERVICE
                            --remaining == 0 -> finish(true)
                        }
                    }

                    private fun finish(ok: Boolean) {
                        runCatching { context.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(ok)
                    }
                }
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

                // NOT_EXPORTED: only our own app (via the PendingIntent below) can trigger it.
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                val sentIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
                )
                smsManager.sendMultipartTextMessage(
                    number, null, parts, ArrayList(List(parts.size) { sentIntent }), null,
                )
            }
        } ?: false
    }
}

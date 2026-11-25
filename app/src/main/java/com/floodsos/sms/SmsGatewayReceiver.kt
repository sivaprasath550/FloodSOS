package com.floodsos.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.floodsos.FloodSosApp
import com.floodsos.core.SmsCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs on the GATEWAY phone for every incoming SMS. If it's a FloodSOS message,
 * it's saved to Room and goes through the same upload path as everything else
 * (outbox + WorkManager), so a gateway with flaky internet doesn't lose messages.
 *
 * Declared in the manifest with android:permission="android.permission.BROADCAST_SMS",
 * so only the system can deliver to it. Without that, any app could send us a fake
 * "SMS received" broadcast.
 */
class SmsGatewayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as FloodSosApp
        if (!app.settings.gatewayMode) return

        // A long (multipart) SMS arrives as several parts in one broadcast; join them.
        val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            .joinToString("") { it.messageBody.orEmpty() }
        val message = SmsCodec.decode(body) ?: return // a normal SMS, not ours: ignore

        // onReceive runs on the main thread and must return quickly. goAsync() lets us
        // finish the database write in the background (we get about 10 seconds).
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isNew = app.repository.save(message) // dedup: the same SMS twice is ignored
                Log.d(TAG, "Gateway got ${message.type} ${message.id} (new=$isNew)")
                if (isNew) app.requestUpload()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsGateway"
    }
}

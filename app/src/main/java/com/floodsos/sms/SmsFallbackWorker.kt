package com.floodsos.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.floodsos.FloodSosApp
import com.floodsos.core.SmsCodec
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Level 2 of the fallback ladder: SMS one message to the gateway phone, unless
 * the internet upload has already delivered it.
 *
 * No network constraint: SMS uses the cellular voice/SMS channel, not data.
 */
class SmsFallbackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FloodSosApp
        val id = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val message = app.repository.get(id) ?: return Result.success()

        // Checked again on every retry: if the internet came back meanwhile, stop.
        if (message.uploaded) return done("$id already uploaded, SMS not needed")
        if (message.smsSent) return done("$id SMS already sent")

        val number = app.settings.gatewayNumber
        if (number.isBlank()) return done("No gateway number set, SMS fallback skipped")
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return done("SEND_SMS not granted, SMS fallback skipped")

        val ok = try {
            SmsSender(app).send(number, SmsCodec.encode(message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "SMS send threw", e)
            false
        }

        return when {
            ok -> {
                app.repository.markSmsSent(id)
                done("SMS sent for $id")
            }
            // No signal right now: back off 30 s, 60 s, 2 min... (about 8 h in total).
            runAttemptCount < MAX_ATTEMPTS -> {
                Log.d(TAG, "SMS failed for $id (attempt ${runAttemptCount + 1}), retrying later")
                Result.retry()
            }
            else -> Result.failure()
        }
    }

    private fun done(reason: String): Result {
        Log.d(TAG, reason)
        return Result.success()
    }

    companion object {
        private const val TAG = "SmsFallback"
        private const val KEY_MESSAGE_ID = "messageId"
        private const val MAX_ATTEMPTS = 10

        fun enqueue(context: Context, messageId: String, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<SmsFallbackWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // One SMS job per message; KEEP means repeated calls can't cause repeated SMS.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("sms-$messageId", ExistingWorkPolicy.KEEP, request)
        }
    }
}

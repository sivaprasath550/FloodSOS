package com.floodsos.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.floodsos.FloodSosApp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Uploads every message that isn't uploaded yet, oldest first.
 *
 * WorkManager runs it only when a network is connected, keeps the job across app
 * kills and reboots, and retries with exponential backoff when we return retry().
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FloodSosApp
        val pending = app.repository.pendingUploads()
        var failed = 0
        var rejected = 0

        for (message in pending) {
            try {
                val created = app.uploader.upload(message)
                // Mark only AFTER the server confirmed. If we crash between the two
                // lines, the next run uploads again: harmless, because it's idempotent.
                app.repository.markUploaded(message.id)
                Log.d(TAG, "Uploaded ${message.id} (new=$created)")
            } catch (e: CancellationException) {
                throw e // WorkManager stopped us (e.g. network lost): don't swallow it
            } catch (e: FirebaseFirestoreException) {
                if (e.code in PERMANENT_ERRORS) {
                    // The security rules said no. Retrying won't change that, and it
                    // shouldn't push the whole queue into a growing backoff.
                    rejected++
                    Log.e(TAG, "Server rejected ${message.id} (${e.code}); not retrying it", e)
                } else {
                    failed++
                    Log.w(TAG, "Upload failed for ${message.id} (${e.code})", e)
                }
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Upload failed for ${message.id}", e)
            }
        }

        Log.d(TAG, "Upload run: ${pending.size - failed - rejected}/${pending.size} ok, $rejected rejected")
        // "Connected" doesn't guarantee working internet. In a flood the tower can be up
        // while its backhaul is down. So a failed run is normal: retry later.
        return if (failed == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val WORK_NAME = "upload-pending"
        private val PERMANENT_ERRORS = setOf(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
        )

        private fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        /** Safe to call often: there is only ever one upload chain. */
        fun enqueue(context: Context) {
            // APPEND_OR_REPLACE: if a run is in progress, queue one more after it, so a
            // message saved mid-run isn't missed. (KEEP would drop this request.)
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
        }

        /**
         * Internet just came back: upload NOW. After many failures, exponential backoff
         * can push the next retry hours away (30 s, 1 min, 2 min, ... up to 5 h), and
         * APPEND would queue behind it. REPLACE cancels the waiting retry and starts
         * fresh. We skip it if an upload is already running, so we don't cut it off.
         */
        suspend fun kickNow(context: Context) {
            val wm = WorkManager.getInstance(context)
            val running = wm.getWorkInfosForUniqueWorkFlow(WORK_NAME).first()
                .any { it.state == WorkInfo.State.RUNNING }
            if (!running) wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request())
        }
    }
}

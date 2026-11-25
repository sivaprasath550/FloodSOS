package com.floodsos.data.remote

import com.floodsos.core.SosMessage
import com.floodsos.core.toUploadFields
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

class FirestoreUploader(
    private val db: FirebaseFirestore,
    private val uploaderId: String,
) {
    /**
     * Writes the message to `messages/{id}` only if that document doesn't exist yet.
     *
     * Why a transaction instead of a plain `set()`:
     *  - `set()` overwrites. If a relayed copy (hops=3) arrived after the original
     *    (hops=0), it would replace the first `receivedAt` and `hops`, and the
     *    latency stats would be wrong. "First write wins" keeps the first arrival.
     *  - Offline, `set()` doesn't fail: Firestore queues it in its own local cache.
     *    That creates a second outbox next to Room. A transaction needs the server,
     *    so offline it fails, and our Room outbox stays the single source of truth.
     *
     * @return true if this call created the document, false if it already existed.
     * Either way the message is safely on the server and can be marked uploaded.
     */
    suspend fun upload(message: SosMessage): Boolean {
        val ref = db.collection(COLLECTION).document(message.id)
        val data = message.toUploadFields(uploaderId) + ("receivedAt" to FieldValue.serverTimestamp())
        return withTimeout(TIMEOUT_MS) {
            db.runTransaction { tx ->
                val exists = tx.get(ref).exists()
                if (!exists) tx.set(ref, data)
                !exists
            }.await()
        }
    }

    companion object {
        const val COLLECTION = "messages"
        private const val TIMEOUT_MS = 20_000L
    }
}

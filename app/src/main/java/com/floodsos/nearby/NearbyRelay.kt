package com.floodsos.nearby

import android.content.Context
import android.util.Log
import com.floodsos.core.ExchangeRecord
import com.floodsos.core.PeerCooldown
import com.floodsos.core.RelayCodec
import com.floodsos.core.RelayCodec.toDomain
import com.floodsos.core.RelayEngine
import com.floodsos.data.SosRepository
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class RelayState(
    val running: Boolean = false,  // the relay service is on
    val scanning: Boolean = false, // inside a duty-cycle "on" window right now
    val connectedPeers: Int = 0,
    val totalReceived: Int = 0,
    val cycleInfo: String = "",
    val log: List<String> = emptyList(),
)

/**
 * Talks to Google Nearby Connections (Bluetooth / BLE / Wi-Fi, chosen by the library).
 * One "session" per connected peer:
 *
 *   found -> (cooldown? tie-break) requestConnection -> both accept -> connected
 *        -> each side sends its batch (last packet has last=true)
 *        -> when we've sent ours AND received theirs -> disconnect
 *
 * RelayService drives it: activate() once, then startScanning()/stopScanning()
 * every duty cycle. stopScanning() stops advertising and discovery but lets
 * exchanges already in progress finish.
 *
 * All Nearby callbacks arrive on the main thread, and everything here runs on
 * Dispatchers.Main, so the maps below need no locks.
 */
class NearbyRelay(
    context: Context,
    private val repository: SosRepository,
    private val myName: String,
    private val onNewMessages: () -> Unit,
    private val engine: RelayEngine = RelayEngine(),
    private val cooldown: PeerCooldown = PeerCooldown(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val receiveLock = Mutex() // process incoming packets one at a time, in order

    private val sessions = mutableMapOf<String, Session>() // endpointId -> session
    private val contacted = mutableSetOf<String>()         // endpointIds handled this window

    private val _state = MutableStateFlow(RelayState())
    val state: StateFlow<RelayState> = _state

    private class Session(val peerName: String) {
        var connectedAt: Long? = null // set once the connection succeeds
        var recorded = false          // logged to the exchanges table already?
        var lastOutgoingPayloadId: Long? = null
        var sentAll = false
        var receivedAll = false
        var sentCount = 0
        var newCount = 0
        var watchdog: Job? = null
    }

    // ---------------- control (called by RelayService) ----------------

    fun activate() {
        if (_state.value.running) return
        _state.update { it.copy(running = true) }
        log("Relay on as $myName")
    }

    fun startScanning() {
        if (!_state.value.running || _state.value.scanning) return
        _state.update { it.copy(scanning = true) }
        contacted.clear() // endpoint ids are fresh each discovery run

        val advertising = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(myName, SERVICE_ID, lifecycleCallback, advertising)
            .addOnFailureListener { log("Advertising failed: ${describe(it)}") }

        val discovery = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, discovery)
            .addOnFailureListener { log("Discovery failed: ${describe(it)}") }
    }

    fun stopScanning() {
        if (!_state.value.scanning) return
        client.stopAdvertising()
        client.stopDiscovery()
        _state.update { it.copy(scanning = false) }
    }

    fun shutdown() {
        stopScanning()
        client.stopAllEndpoints()
        sessions.values.forEach { it.watchdog?.cancel() }
        sessions.clear()
        contacted.clear()
        _state.update { it.copy(running = false, connectedPeers = 0, cycleInfo = "") }
        log("Relay off")
    }

    fun setCycleInfo(text: String) = _state.update { it.copy(cycleInfo = text) }

    /** We have something new (our own SOS): share it with everyone, even recent peers. */
    fun resetCooldowns() = cooldown.clear()

    // ---------------- discovery ----------------

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            if (!cooldown.canSync(info.endpointName, clock())) {
                Log.d(TAG, "Skip ${info.endpointName}: synced recently")
                return
            }
            log("Found ${info.endpointName}")
            if (engine.shouldInitiate(myName, info.endpointName)) {
                connect(endpointId)
            } else {
                // The other phone should ask. But Bluetooth discovery isn't always
                // two-way, so if nothing has happened after a few seconds, ask anyway.
                scope.launch {
                    delay(FALLBACK_CONNECT_DELAY_MS)
                    if (_state.value.running && endpointId !in contacted) {
                        log("${info.endpointName} didn't connect, connecting myself")
                        connect(endpointId)
                    }
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost $endpointId")
        }
    }

    private fun connect(endpointId: String) {
        if (!contacted.add(endpointId)) return
        client.requestConnection(myName, endpointId, lifecycleCallback)
            .addOnFailureListener { e ->
                val code = (e as? ApiException)?.statusCode
                // ALREADY_CONNECTED: the other phone's request won the race. That's fine.
                if (code != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                    log("Connect failed: ${describe(e)}")
                }
            }
    }

    // ---------------- connection lifecycle ----------------

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            contacted.add(endpointId)
            sessions[endpointId] = Session(info.endpointName)
            // Auto-accept, without comparing the authentication digits. Messages are
            // public by design, so pairing would add friction (bad in an emergency)
            // without protecting anything secret.
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val session = sessions[endpointId] ?: return
            if (!result.status.isSuccess) {
                log("Connection to ${session.peerName} failed: " +
                    ConnectionsStatusCodes.getStatusCodeString(result.status.statusCode))
                endSession(endpointId)
                return
            }
            log("Connected to ${session.peerName}")
            session.connectedAt = clock()
            updatePeerCount()
            session.watchdog = scope.launch {
                delay(SESSION_TIMEOUT_MS) // never stay connected forever (battery, slots)
                log("Exchange with ${session.peerName} timed out")
                disconnect(endpointId)
            }
            scope.launch { sendBatch(endpointId, session) }
        }

        override fun onDisconnected(endpointId: String) {
            sessions[endpointId]?.let { log("${it.peerName} disconnected") }
            endSession(endpointId)
        }
    }

    private suspend fun sendBatch(endpointId: String, session: Session) {
        val toSend = engine.selectToSend(repository.all())
        val packets = RelayCodec.encode(toSend)
        session.sentCount = toSend.size
        packets.forEachIndexed { i, bytes ->
            val payload = Payload.fromBytes(bytes)
            if (i == packets.lastIndex) session.lastOutgoingPayloadId = payload.id
            client.sendPayload(endpointId, payload)
        }
    }

    // ---------------- payloads ----------------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val packet = RelayCodec.decode(bytes)
            if (packet == null) {
                log("Ignored an unreadable packet")
                return
            }
            scope.launch {
                receiveLock.withLock {
                    val incoming = packet.messages.map { it.toDomain() }
                    val known = repository.existingIds(incoming.map { it.id })
                    val fresh = engine.acceptIncoming(incoming, known)
                    // save() uses INSERT OR IGNORE: the database is the final dedup guard.
                    val stored = fresh.count { repository.save(it) }
                    if (stored > 0) {
                        _state.update { it.copy(totalReceived = it.totalReceived + stored) }
                        onNewMessages() // e.g. try uploading if this phone has internet
                    }
                    sessions[endpointId]?.let { s ->
                        s.newCount += stored
                        if (packet.last) {
                            s.receivedAll = true
                            finishIfDone(endpointId, s)
                        }
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val session = sessions[endpointId] ?: return
            if (update.payloadId != session.lastOutgoingPayloadId) return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    session.sentAll = true
                    finishIfDone(endpointId, session)
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    log("Sending to ${session.peerName} failed")
                    disconnect(endpointId)
                }
            }
        }
    }

    private fun finishIfDone(endpointId: String, s: Session) {
        if (!s.sentAll || !s.receivedAll) return
        cooldown.markSynced(s.peerName, clock())
        log("Exchanged with ${s.peerName}: sent ${s.sentCount}, got ${s.newCount} new")
        recordExchange(s, success = true)
        disconnect(endpointId)
    }

    /** Logs the meeting once. Anything that ends before finishIfDone counts as a failure. */
    private fun recordExchange(s: Session, success: Boolean) {
        val start = s.connectedAt ?: return // never connected: not an exchange
        if (s.recorded) return
        s.recorded = true
        val record = ExchangeRecord(s.peerName, start, clock() - start, s.sentCount, s.newCount, success)
        scope.launch { repository.recordExchange(record) }
    }

    /** Our own disconnect does NOT trigger onDisconnected, so clean up here too. */
    private fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        endSession(endpointId)
    }

    private fun endSession(endpointId: String) {
        sessions.remove(endpointId)?.let { s ->
            s.watchdog?.cancel()
            recordExchange(s, success = false) // no-op if it already finished successfully
        }
        updatePeerCount()
    }

    private fun updatePeerCount() {
        _state.update { it.copy(connectedPeers = sessions.size) }
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        val stamped = "${LocalTime.now().format(TIME)}  $line"
        _state.update { it.copy(log = (listOf(stamped) + it.log).take(MAX_LOG_LINES)) }
    }

    private fun describe(e: Exception): String =
        (e as? ApiException)?.let { ConnectionsStatusCodes.getStatusCodeString(it.statusCode) } ?: e.message.orEmpty()

    private companion object {
        const val TAG = "NearbyRelay"
        const val SERVICE_ID = "com.floodsos.relay" // only phones with the same id see each other
        // P2P_CLUSTER: many-to-many, every phone is equal. Right for a mesh of strangers.
        // (P2P_STAR = one hub, higher bandwidth; P2P_POINT_TO_POINT = exactly two phones.)
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        const val FALLBACK_CONNECT_DELAY_MS = 5_000L
        const val SESSION_TIMEOUT_MS = 30_000L
        const val MAX_LOG_LINES = 15
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

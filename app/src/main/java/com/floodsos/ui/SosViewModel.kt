package com.floodsos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.floodsos.AppSettings
import com.floodsos.FloodSosApp
import com.floodsos.core.MessageType
import com.floodsos.core.SosMessage
import com.floodsos.data.SosRepository
import com.floodsos.location.LocationProvider
import com.floodsos.nearby.NearbyRelay
import com.floodsos.nearby.RelayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the screen needs to draw itself, in one immutable object. */
data class SosUiState(
    val note: String = "",
    val isSending: Boolean = false,
    val status: String? = null,
    val gatewayNumber: String = "",
    val gatewayMode: Boolean = false,
    val messages: List<SosMessage> = emptyList(),
)

/** The parts of the state that only the ViewModel changes (messages come from Room). */
private data class FormState(
    val note: String = "",
    val isSending: Boolean = false,
    val status: String? = null,
    val gatewayNumber: String = "",
    val gatewayMode: Boolean = false,
)

class SosViewModel(
    private val repository: SosRepository,
    private val locationProvider: LocationProvider,
    private val settings: AppSettings,
    private val deviceId: String,
    private val deliver: (SosMessage) -> Boolean, // starts the fallback ladder; true = online
    nearby: NearbyRelay,
    private val setRelayEnabled: (Boolean) -> Unit, // starts/stops RelayService
) : ViewModel() {

    /** Owned by the Application, not this ViewModel: the relay outlives the screen. */
    val relayState: StateFlow<RelayState> = nearby.state

    fun setRelay(enabled: Boolean) = setRelayEnabled(enabled)

    fun onNearbyPermissionDenied() {
        form.update { it.copy(status = "Nearby devices + precise location are needed to relay over Bluetooth.") }
    }

    private val form = MutableStateFlow(
        FormState(gatewayNumber = settings.gatewayNumber, gatewayMode = settings.gatewayMode)
    )

    val uiState: StateFlow<SosUiState> =
        combine(form, repository.observeMessages()) { f, messages ->
            SosUiState(f.note, f.isSending, f.status, f.gatewayNumber, f.gatewayMode, messages)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SosUiState())

    fun onNoteChange(text: String) {
        form.update { it.copy(note = text.take(SosMessage.MAX_NOTE_LENGTH)) }
    }

    /** Call only after location permission is granted. */
    fun send(type: String) {
        if (form.value.isSending) return // ignore double taps
        form.update { it.copy(isSending = true, status = "Getting GPS location…") }

        viewModelScope.launch {
            try {
                val fix = locationProvider.currentLocation()
                if (fix == null) {
                    form.update {
                        it.copy(status = "Couldn't get a location. Turn on Location (GPS) and try again.")
                    }
                    return@launch
                }
                val message = SosMessage.create(
                    senderId = deviceId,
                    type = type,
                    lat = fix.lat,
                    lng = fix.lng,
                    note = form.value.note,
                    now = System.currentTimeMillis(),
                )
                repository.save(message)         // 1) durable local copy first (outbox)
                val online = deliver(message)    // 2) then the fallback ladder
                val label = if (type == MessageType.SOS) "SOS" else "SAFE"
                val how = when {
                    online -> "Uploading over the internet."
                    settings.gatewayNumber.isNotBlank() -> "No internet: sending SMS to the gateway and relaying to nearby phones."
                    else -> "No internet: relaying to nearby phones. Set a gateway number below for SMS fallback."
                }
                form.update { it.copy(note = "", status = "$label saved (±${fix.accuracyMeters.toInt()} m). $how") }
            } finally {
                form.update { it.copy(isSending = false) }
            }
        }
    }

    fun onPermissionDenied() {
        form.update { it.copy(status = "Location permission is needed so rescuers can find you.") }
    }

    fun onGatewayNumberChange(text: String) {
        form.update { it.copy(gatewayNumber = text) }
    }

    /** @return true if the number looked valid and was saved. */
    fun saveGatewayNumber(): Boolean {
        val cleaned = form.value.gatewayNumber.replace(Regex("[\\s()-]"), "")
        if (cleaned.isNotEmpty() && !PHONE_REGEX.matches(cleaned)) {
            form.update { it.copy(status = "That doesn't look like a phone number.") }
            return false
        }
        settings.gatewayNumber = cleaned
        form.update {
            it.copy(
                gatewayNumber = cleaned,
                status = if (cleaned.isEmpty()) "SMS fallback turned off." else "Gateway number saved.",
            )
        }
        return cleaned.isNotEmpty()
    }

    fun setGatewayMode(enabled: Boolean) {
        settings.gatewayMode = enabled
        form.update {
            it.copy(
                gatewayMode = enabled,
                status = if (enabled) "Gateway mode ON: incoming SOS SMS will be uploaded." else "Gateway mode off.",
            )
        }
    }

    fun onSmsPermissionDenied(what: String) {
        form.update { it.copy(status = "SMS permission denied: $what won't work.") }
    }

    companion object {
        private val PHONE_REGEX = Regex("^\\+?[0-9]{3,15}$") // 3 digits allows emulator numbers like 5554

        /** Tells Android how to build this ViewModel with its dependencies. */
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FloodSosApp
                SosViewModel(
                    app.repository, app.locationProvider, app.settings, app.deviceId,
                    app::deliver, app.nearby, app::setRelayEnabled,
                )
            }
        }
    }
}

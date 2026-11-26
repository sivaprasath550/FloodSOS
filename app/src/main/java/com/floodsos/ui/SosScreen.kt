package com.floodsos.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import com.floodsos.nearby.NearbyPermissions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.floodsos.core.MessageType
import com.floodsos.core.SosMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun hasLocationPermission(context: Context) = LOCATION_PERMISSIONS.any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun SosScreen(viewModel: SosViewModel, contentPadding: PaddingValues, onOpenStats: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Remember which button was tapped while the permission dialog is showing.
    var pendingType by rememberSaveable { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val type = pendingType
        pendingType = null
        // FINE or COARSE is enough to proceed; COARSE-only just means lower accuracy.
        if (results.values.any { it } && type != null) viewModel.send(type) else viewModel.onPermissionDenied()
    }

    fun onTap(type: String) {
        if (hasLocationPermission(context)) {
            viewModel.send(type)
        } else {
            pendingType = type
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("FloodSOS", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChange,
                label = { Text("Short note (optional)") },
                placeholder = { Text("e.g. 3 people, 2nd floor") },
                supportingText = { Text("${state.note.length}/${SosMessage.MAX_NOTE_LENGTH}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = { onTap(MessageType.SOS) },
                enabled = !state.isSending,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                modifier = Modifier.size(200.dp),
            ) {
                if (state.isSending) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Text("SOS", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        item {
            OutlinedButton(onClick = { onTap(MessageType.SAFE) }, enabled = !state.isSending) {
                Text("I'm safe")
            }
        }
        state.status?.let { status -> item { Text(status, style = MaterialTheme.typography.bodyMedium) } }
        item { RelayCard(viewModel) }
        item {
            OutlinedButton(onClick = onOpenStats, modifier = Modifier.fillMaxWidth()) {
                Text("Field test stats")
            }
        }
        item { SmsSettingsCard(state, viewModel) }
        item {
            Text(
                "Saved on this phone (${state.messages.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        items(state.messages, key = { it.id }) { MessageRow(it) }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")

@Composable
private fun MessageRow(m: SosMessage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val time = Instant.ofEpochMilli(m.createdAt).atZone(ZoneId.systemDefault()).format(timeFormat)
            Text("${m.type} · $time", fontWeight = FontWeight.Bold)
            Text("%.5f, %.5f".format(m.lat, m.lng))
            if (m.note.isNotEmpty()) Text("\"${m.note}\"")
            val sms = if (m.smsSent) " · SMS sent" else ""
            Text(
                "hops=${m.hops} · ${if (m.uploaded) "uploaded" else "not uploaded"}$sms · id=${m.id.take(8)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RelayCard(viewModel: SosViewModel) {
    val relay by viewModel.relayState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // The notification permission is optional: the relay works without it, just hidden.
        if (NearbyPermissions.allGranted(context)) viewModel.setRelay(true) else viewModel.onNearbyPermissionDenied()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nearby relay", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pass messages phone-to-phone over Bluetooth when there's no signal.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = relay.running,
                    onCheckedChange = { on ->
                        when {
                            !on -> viewModel.setRelay(false)
                            NearbyPermissions.allGranted(context) &&
                                NearbyPermissions.notificationsGranted(context) -> viewModel.setRelay(true)
                            else -> launcher.launch(NearbyPermissions.required() + NearbyPermissions.notification())
                        }
                    },
                )
            }
            if (relay.running || relay.log.isNotEmpty()) {
                val phase = when {
                    !relay.running -> "Off"
                    relay.scanning -> "Scanning"
                    else -> "Sleeping"
                }
                Text(
                    "$phase · connected: ${relay.connectedPeers} · received from peers: ${relay.totalReceived}",
                    fontWeight = FontWeight.Bold,
                )
                if (relay.cycleInfo.isNotEmpty()) {
                    Text(relay.cycleInfo, style = MaterialTheme.typography.bodySmall)
                }
                relay.log.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun has(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Set up BEFORE an emergency: asking for SMS permission while someone is pressing
 * SOS would be bad UX, so it's requested here, when the user saves a gateway number.
 */
@Composable
private fun SmsSettingsCard(state: SosUiState, viewModel: SosViewModel) {
    val context = LocalContext.current

    val sendSmsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) viewModel.onSmsPermissionDenied("SMS fallback")
    }
    val receiveSmsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setGatewayMode(true) else viewModel.onSmsPermissionDenied("gateway mode")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SMS fallback", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.gatewayNumber,
                onValueChange = viewModel::onGatewayNumberChange,
                label = { Text("Gateway phone number") },
                placeholder = { Text("+91…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                val saved = viewModel.saveGatewayNumber()
                if (saved && !has(context, Manifest.permission.SEND_SMS)) {
                    sendSmsLauncher.launch(Manifest.permission.SEND_SMS)
                }
            }) { Text("Save") }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Gateway mode", fontWeight = FontWeight.Bold)
                    Text(
                        "Turn on only on the phone that has internet and receives SOS SMS.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.gatewayMode,
                    onCheckedChange = { on ->
                        if (on && !has(context, Manifest.permission.RECEIVE_SMS)) {
                            receiveSmsLauncher.launch(Manifest.permission.RECEIVE_SMS)
                        } else {
                            viewModel.setGatewayMode(on)
                        }
                    },
                )
            }
        }
    }
}

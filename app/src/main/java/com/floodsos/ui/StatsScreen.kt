package com.floodsos.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.floodsos.core.DurationSummary
import com.floodsos.core.FieldStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun StatsScreen(viewModel: StatsViewModel, contentPadding: PaddingValues, onBack: () -> Unit) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Field test stats", style = MaterialTheme.typography.headlineSmall)
            }
            Text("This phone: ${viewModel.deviceShortId}", style = MaterialTheme.typography.bodySmall)
        }

        val s = stats
        if (s == null) {
            item { Text("Loading…") }
        } else {
            item { OwnCard(s) }
            item { CarriedCard(s) }
            item { HopsCard(s.hopCounts) }
            item { ExchangesCard(s) }
        }

        item {
            Button(
                onClick = {
                    scope.launch {
                        val json = viewModel.exportJson()
                        shareExport(context, json, viewModel.deviceShortId)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export data (JSON) for analysis") }
            Text(
                "Contains locations and notes. Share only with your test team.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun OwnCard(s: FieldStats) = StatCard("My messages") {
    Line("Sent", "${s.ownSos + s.ownSafe}  (SOS ${s.ownSos}, SAFE ${s.ownSafe})")
    Line("Uploaded by this phone", "${s.ownUploadedHere}")
    Line("Waiting (not uploaded yet)", "${s.ownWaiting}")
    Line("SMS fallback sent", "${s.ownSmsSent}")
    Summary("Upload delay", s.ownUploadDelay)
    Note("Only counts uploads done by this phone. If a relay delivered it, see the analysis script.")
}

@Composable
private fun CarriedCard(s: FieldStats) = StatCard("Carried for others") {
    Line("Received over Bluetooth relay", "${s.othersViaRelay}")
    Line("Received as SMS gateway", "${s.othersViaSms}")
    Line("Uploaded by this phone", "${s.othersUploadedHere}")
    Summary("Carry time (received → uploaded)", s.carryTime)
}

@Composable
private fun HopsCard(hops: Map<Int, Int>) = StatCard("Hop distribution (all messages on this phone)") {
    if (hops.isEmpty()) {
        Note("No messages yet.")
    } else {
        val max = hops.values.max().toFloat()
        hops.forEach { (hop, count) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text("$hop hops", modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth((count / max).coerceAtLeast(0.03f))
                            .height(14.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Text("$count", modifier = Modifier.width(40.dp).padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ExchangesCard(s: FieldStats) = StatCard("Bluetooth exchanges") {
    Line("Meetings", "${s.exchanges}  (${s.exchangesOk} completed)")
    Line("Different phones met", "${s.uniquePeers}")
    Line("New messages received", "${s.receivedViaRelay}")
    Summary("Exchange duration (completed)", s.exchangeDuration)
}

// ---------------- small building blocks ----------------

@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Summary(label: String, d: DurationSummary) {
    if (d.count == 0) {
        Line(label, "–")
    } else {
        Line(label, "median ${formatDuration(d.medianMs)}")
        Line("   p90 / max (n=${d.count})", "${formatDuration(d.p90Ms)} / ${formatDuration(d.maxMs)}")
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

internal fun formatDuration(ms: Long?): String {
    if (ms == null) return "–"
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

/**
 * Writes the export to the app's cache and opens the Android share sheet
 * (Drive, Gmail, WhatsApp, Nearby Share…). FileProvider gives the receiving app
 * temporary read access to just this file, without any storage permission.
 */
private suspend fun shareExport(context: Context, json: String, deviceShort: String) {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "exports").apply { mkdirs() }
            .resolve("floodsos-$deviceShort-$stamp.json")
            .apply { writeText(json) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share field-test data"))
    Toast.makeText(context, "Saved ${file.name}", Toast.LENGTH_SHORT).show()
}

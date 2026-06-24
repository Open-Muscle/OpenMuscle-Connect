package org.openmuscle.connect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.openmuscle.connect.capture.SessionInfo
import org.openmuscle.connect.ui.theme.OmAccent
import org.openmuscle.connect.ui.theme.OmBackground
import org.openmuscle.connect.ui.theme.OmSurface
import org.openmuscle.connect.viewmodel.CaptureUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    onToggleRecord: () -> Unit,
    onManualModeChange: (Boolean) -> Unit,
    onManualLabelChange: (Int, Int) -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Capture") },
                actions = { TextButton(onClick = onBack) { Text("Heatmap") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = OmBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Rows", state.rows.toString())
                Stat("Match", "%.0f%%".format(state.matchRate * 100))
                Stat("Elapsed", "%.1fs".format(state.elapsedMs / 1000f))
            }
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onToggleRecord,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.recording) MaterialTheme.colorScheme.error else OmAccent,
                ),
            ) {
                Text(if (state.recording) "Stop recording" else "Start recording")
            }

            Spacer(Modifier.height(20.dp))
            ManualLabelControls(state, onManualModeChange, onManualLabelChange)

            Spacer(Modifier.height(20.dp))
            Text("Sessions", color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            if (state.sessions.isEmpty()) {
                Text("No recorded sessions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.sessions, key = { it.name }) { session ->
                        SessionRow(session, onShare = { onShare(session.name) }, onDelete = { onDelete(session.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualLabelControls(
    state: CaptureUiState,
    onManualModeChange: (Boolean) -> Unit,
    onManualLabelChange: (Int, Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Manual labels", color = MaterialTheme.colorScheme.onBackground)
        Switch(checked = state.manualMode, onCheckedChange = onManualModeChange)
    }
    if (state.manualMode) {
        for (i in state.manualLabel.indices) {
            val value = state.manualLabel[i]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("label_$i", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value.toString(), color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onManualLabelChange(i, it.toInt()) },
                valueRange = 0f..4095f,
            )
        }
    } else {
        Text(
            "Pairing FlexGrid frames with LASK5 labels within 100 ms.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SessionRow(session: SessionInfo, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OmSurface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(session.name, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                Text("${session.sizeBytes} bytes", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Row {
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace)
    }
}

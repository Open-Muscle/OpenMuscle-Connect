package org.openmuscle.connect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.openmuscle.connect.domain.ImuSnapshot
import org.openmuscle.connect.ui.theme.OmBackground
import org.openmuscle.connect.ui.theme.OmSurface
import org.openmuscle.connect.viewmodel.ConnectUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ConnectUiState,
    onToggleListen: () -> Unit,
    onBack: () -> Unit,
    onCapture: () -> Unit,
    onInfer: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onScanRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Open Muscle Connect") },
                actions = {
                    TextButton(onClick = onCapture) { Text("Capture") }
                    TextButton(onClick = onInfer) { Text("Predict") }
                    TextButton(onClick = onBack) { Text("Devices") }
                },
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
            StatusRow(state)
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(15f / 4f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OmSurface),
                contentAlignment = Alignment.Center,
            ) {
                HeatmapView(state.frame, Modifier.fillMaxSize())
                if (state.frame == null) {
                    Text(
                        text = "Waiting for FlexGrid frames on UDP 3141",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onToggleListen, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.listening) "Stop listening" else "Start listening")
            }

            // Prefer the fast per-frame data.imu (~sensor rate) over the slow status meta imu.
            val liveImu = state.frame?.imu
            (liveImu ?: state.status?.imu)?.let { imu ->
                Spacer(Modifier.height(16.dp))
                ImuCard(imu, fast = liveImu != null)
            }

            if (state.controlConnected) {
                Spacer(Modifier.height(16.dp))
                ControlCard(state, onStartStream, onStopStream, onScanRate)
            }
        }
    }
}

@Composable
private fun ControlCard(
    state: ConnectUiState,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onScanRate: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OmSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Device control", color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStartStream) { Text("Start stream") }
                OutlinedButton(onClick = onStopStream) { Text("Stop stream") }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Scan rate ${state.scanRate} Hz",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onScanRate(state.scanRate - 10) }) { Text("-") }
                    OutlinedButton(onClick = { onScanRate(state.scanRate + 10) }) { Text("+") }
                }
            }
            state.lastCommand?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ImuCard(imu: ImuSnapshot, fast: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OmSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "IMU" + (imu.variant?.let { "  ($it)" } ?: ""),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            ImuRow("Gyro", imu.gyro)
            Spacer(Modifier.height(4.dp))
            ImuRow("Accel", imu.accel)
            Spacer(Modifier.height(6.dp))
            // Only surface temp when plausible; the firmware temp_c has read out of
            // range (e.g. -1894 C) on the TOKMAS variant, so don't present garbage.
            val tempStr = imu.tempC?.takeIf { it in -40.0..150.0 }?.let { "temp %.1f C  -  ".format(it) }.orEmpty()
            val cadence = if (fast) "live (sensor rate)" else "~5s (status cadence)"
            Text(
                tempStr + "raw counts, $cadence",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ImuRow(label: String, axes: List<Int>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        Text(
            if (axes.size >= 3) "x %6d  y %6d  z %6d".format(axes[0], axes[1], axes[2]) else "--",
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatusRow(state: ConnectUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Stat("Device", state.deviceId ?: "none")
        Stat("Rate", "%.0f Hz".format(state.hz))
        Stat("Battery", state.status?.batteryPct?.let { "$it%" } ?: "--")
        Stat("RSSI", state.status?.rssi?.let { "$it dBm" } ?: "--")
        Stat("Frames", state.packetCount.toString())
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace)
    }
}

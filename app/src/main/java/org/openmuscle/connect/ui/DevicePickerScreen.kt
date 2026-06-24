package org.openmuscle.connect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.openmuscle.connect.domain.DiscoveredDevice
import org.openmuscle.connect.domain.Role
import org.openmuscle.connect.ui.theme.OmBackground
import org.openmuscle.connect.ui.theme.OmSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerScreen(
    devices: List<DiscoveredDevice>,
    onSelect: (DiscoveredDevice) -> Unit,
    onSkip: () -> Unit,
    onRename: (String, String) -> Unit,
    onSetRole: (String, Role?) -> Unit,
    onMultiCapture: () -> Unit,
    onProvisionDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<DiscoveredDevice?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Select a FlexGrid") },
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
            if (devices.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Searching for devices on Wi-Fi.\nPower on a FlexGrid, or run the simulator:\n" +
                            "python tools/openmuscle_sim.py --target <phone-ip> --announce",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            onClick = { onSelect(device) },
                            onRename = { editing = device },
                            onSetRole = { role -> onSetRole(device.id, role) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Show any device")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onMultiCapture, modifier = Modifier.fillMaxWidth()) {
                Text("Multi-device capture (tagged sources)")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onProvisionDevice, modifier = Modifier.fillMaxWidth()) {
                Text("Set up a new device")
            }
        }
    }

    editing?.let { device ->
        RenameDialog(
            initial = device.nickname ?: device.id,
            onConfirm = { name ->
                onRename(device.id, name)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onSetRole: (Role?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = OmSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        device.nickname ?: device.id,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        listOfNotNull(
                            device.deviceType,
                            device.host,
                            device.transport.name.lowercase(),
                            device.rssi?.let { "$it dBm" },
                        ).joinToString("  "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onRename) { Text("Rename") }
            }
            // Capture role: tag this device left/right/labeler for multi-device
            // capture (schema v2). Tapping the active chip clears it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Role",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                for (r in Role.entries) {
                    FilterChip(
                        selected = device.role == r,
                        onClick = { onSetRole(if (device.role == r) null else r) },
                        label = { Text(r.wire) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename device") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Nickname") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

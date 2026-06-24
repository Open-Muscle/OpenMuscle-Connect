package org.openmuscle.connect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.openmuscle.connect.provisioning.UnprovisionedDevice
import org.openmuscle.connect.ui.theme.OmBackground
import org.openmuscle.connect.ui.theme.OmSurface
import org.openmuscle.connect.viewmodel.ProvisioningStep
import org.openmuscle.connect.viewmodel.ProvisioningUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisioningScreen(
    state: ProvisioningUiState,
    onScan: () -> Unit,
    onRequestPermission: () -> Unit,
    onSelectDevice: (UnprovisionedDevice) -> Unit,
    onSetPsk: (String) -> Unit,
    onSubmitPsk: () -> Unit,
    onConfirmIdentity: () -> Unit,
    onSetSsid: (String) -> Unit,
    onSetPassword: (String) -> Unit,
    onProvision: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Set up a new device") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = OmBackground,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            when (state.step) {
                ProvisioningStep.IDLE, ProvisioningStep.SCANNING -> {
                    Text(
                        "Put the device in setup mode (it broadcasts an OM-... Wi-Fi), then scan.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.step == ProvisioningStep.SCANNING) "Scanning..." else "Scan")
                    }
                }

                ProvisioningStep.NEED_PERMISSION -> {
                    Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant location permission")
                    }
                }

                ProvisioningStep.PICK_DEVICE -> {
                    if (state.devices.isEmpty()) {
                        Text("No devices in setup mode found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        state.devices.forEach { d ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = OmSurface),
                                modifier = Modifier.fillMaxWidth().clickable { onSelectDevice(d) },
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(d.deviceId, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
                                    Text(d.ssid, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan again") }
                }

                ProvisioningStep.ENTER_PSK -> {
                    Text(
                        "Read the 10-character code on the ${state.selected?.deviceId ?: "device"} screen and enter it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.psk,
                        onValueChange = onSetPsk,
                        singleLine = true,
                        label = { Text("Device code (10 characters)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = onSubmitPsk, modifier = Modifier.fillMaxWidth()) { Text("Join") }
                }

                ProvisioningStep.CONNECTING -> Text("Joining the device's setup Wi-Fi...", color = MaterialTheme.colorScheme.onSurfaceVariant)

                ProvisioningStep.CONFIRM_IDENTITY -> {
                    val info = state.info
                    Text("Found:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${info?.id}\n${info?.dev}  fw ${info?.fw}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                    )
                    Button(onClick = onConfirmIdentity, modifier = Modifier.fillMaxWidth()) { Text("Yes, this is my device") }
                    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Not this one") }
                }

                ProvisioningStep.ENTER_CREDS -> {
                    OutlinedTextField(
                        value = state.ssid,
                        onValueChange = onSetSsid,
                        singleLine = true,
                        label = { Text("Your Wi-Fi name (SSID)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onSetPassword,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Wi-Fi password") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = onProvision, modifier = Modifier.fillMaxWidth()) { Text("Send to device") }
                }

                ProvisioningStep.PROVISIONING -> Text("Sending credentials...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProvisioningStep.WAITING_JOIN -> Text("Waiting for the device to join your Wi-Fi...", color = MaterialTheme.colorScheme.onSurfaceVariant)

                ProvisioningStep.SUCCESS -> {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }

                ProvisioningStep.FAILURE -> {
                    Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

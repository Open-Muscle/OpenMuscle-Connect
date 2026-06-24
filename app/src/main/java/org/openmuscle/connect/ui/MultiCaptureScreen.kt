package org.openmuscle.connect.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.openmuscle.connect.ui.theme.OmBackground
import org.openmuscle.connect.ui.theme.OmSurface
import org.openmuscle.connect.viewmodel.MultiCaptureUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiCaptureScreen(
    state: MultiCaptureUiState,
    onToggleRecord: () -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Multi-device capture") },
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
            Text(
                "Tagged sources",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.sources.isEmpty()) {
                Text(
                    "No devices tagged yet. Go to the device picker and tag each band Left or Right and the LASK5 Labeler.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.sources.forEach { s ->
                    Card(colors = CardDefaults.cardColors(containerColor = OmSurface)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(s.deviceId, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
                            Text(
                                s.role.wire + (if (s.subscribable) "" else "  (no address)"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Button(onClick = onToggleRecord, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.recording) "Stop recording" else "Start recording")
            }
            val statusLine = state.status ?: if (state.recording) "recording" else "idle"
            Text(
                "rows ${state.rows}   match ${"%.0f".format(state.matchRate * 100)}%   ${"%.1f".format(state.elapsedMs / 1000f)}s   [$statusLine]",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )

            Spacer(Modifier.height(4.dp))
            Text("Sessions", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.sessions, key = { it.name }) { session ->
                    Card(colors = CardDefaults.cardColors(containerColor = OmSurface)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                session.name,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Share",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickableText { onShare(session.name) },
                                )
                                Text(
                                    "Delete",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.clickableText { onDelete(session.name) },
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier = clickable { onClick() }

package org.openmuscle.connect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.openmuscle.connect.ui.theme.OmAccent
import org.openmuscle.connect.ui.theme.OmBackground
import org.openmuscle.connect.ui.theme.OmSurface
import org.openmuscle.connect.viewmodel.InferenceUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceScreen(
    state: InferenceUiState,
    onLoadModel: () -> Unit,
    onTogglePredict: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    maxValue: Float = 4095f,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Predict") },
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
                OutlinedButton(onClick = onLoadModel) { Text("Load model") }
                Text(
                    if (state.modelLoaded) "model loaded" else "no model",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onTogglePredict,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.modelLoaded,
            ) {
                Text(if (state.predicting) "Stop predicting" else "Start predicting")
            }

            Spacer(Modifier.height(20.dp))
            Text("Predicted pistons", color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            if (state.prediction.isEmpty()) {
                Text(
                    "Load a model and start predicting to see live output.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(OmSurface),
                ) {
                    HandPoseView(state.prediction, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(16.dp))
                state.prediction.forEachIndexed { i, value ->
                    PistonBar(index = i, value = value, fraction = (value / maxValue).coerceIn(0f, 1f))
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "inference ${state.latencyMs} ms",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun PistonBar(index: Int, value: Float, fraction: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Piston $index", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text("%.0f".format(value), color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(OmSurface),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(OmAccent),
        )
    }
}

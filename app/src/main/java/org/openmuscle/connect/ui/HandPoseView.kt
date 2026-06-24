package org.openmuscle.connect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.openmuscle.connect.ui.theme.OmAccent

/**
 * A simple top-down hand whose four fingers curl with the predicted piston
 * values (0 = extended, max = fully curled). Purely a visual aid for the Predict
 * screen; the numeric bars carry the exact values. Maps the 4-output model to
 * index/middle/ring/pinky; a thumb stub is drawn fixed for shape.
 */
@Composable
fun HandPoseView(
    values: List<Float>,
    modifier: Modifier = Modifier,
    maxValue: Float = 4095f,
) {
    val palmColor = Color(0xFF31424C)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val palmH = h * 0.30f
        val palmTop = h - palmH
        val gap = w * 0.04f
        val fingerW = (w - gap * 5f) / 4f
        val maxFingerLen = palmTop - h * 0.05f

        // Thumb: a fixed stub angled off the lower-left of the palm.
        drawRoundRect(
            color = palmColor,
            topLeft = Offset(0f, palmTop + palmH * 0.25f),
            size = Size(gap * 2.2f, palmH * 0.55f),
            cornerRadius = CornerRadius(gap, gap),
        )

        // Four fingers: shorter as the piston value (flexion) rises.
        for (i in 0 until 4) {
            val flexion = ((values.getOrNull(i) ?: 0f) / maxValue).coerceIn(0f, 1f)
            val len = maxFingerLen * (1f - 0.7f * flexion)
            val x = gap + i * (fingerW + gap)
            drawRoundRect(
                color = OmAccent,
                topLeft = Offset(x, palmTop - len),
                size = Size(fingerW, len),
                cornerRadius = CornerRadius(fingerW / 2f, fingerW / 2f),
            )
        }

        // Palm last so finger roots tuck under it.
        drawRoundRect(
            color = palmColor,
            topLeft = Offset(gap, palmTop),
            size = Size(w - gap * 2f, palmH),
            cornerRadius = CornerRadius(palmH * 0.3f, palmH * 0.3f),
        )
    }
}

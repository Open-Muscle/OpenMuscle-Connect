package org.openmuscle.connect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.openmuscle.connect.domain.SensorFrame

/**
 * Draws a FlexGrid frame as a heatmap: [cols] wide by [rows] tall (15x4 for
 * V3/V4). Cell (r, c) reads `matrix[c][r]` because the wire matrix is
 * column-major (see docs/WIRE-FORMAT.md section 2.1). Colours use a plasma-style
 * ramp matching the PC app's heatmap.
 */
@Composable
fun HeatmapView(
    frame: SensorFrame?,
    modifier: Modifier = Modifier,
    maxValue: Int = 4095,
) {
    Canvas(modifier = modifier) {
        if (frame == null || frame.rows == 0 || frame.cols == 0) return@Canvas
        val rows = frame.rows
        val cols = frame.cols
        val cellW = size.width / cols
        val cellH = size.height / rows
        for (r in 0 until rows) {
            val rowVals = frame.matrix
            for (c in 0 until cols) {
                val v = rowVals[c][r].coerceIn(0, maxValue)
                val t = v.toFloat() / maxValue
                drawRect(
                    color = plasma(t),
                    topLeft = Offset(c * cellW, r * cellH),
                    size = Size(cellW, cellH),
                )
            }
        }
    }
}

// Six-stop approximation of matplotlib's "plasma" colormap.
private val plasmaStops = arrayOf(
    0.00f to Color(0xFF0D0887),
    0.25f to Color(0xFF6A00A8),
    0.50f to Color(0xFFB12A90),
    0.75f to Color(0xFFE16462),
    0.90f to Color(0xFFFCA636),
    1.00f to Color(0xFFF0F921),
)

private fun plasma(t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    for (i in 0 until plasmaStops.size - 1) {
        val (t0, c0) = plasmaStops[i]
        val (t1, c1) = plasmaStops[i + 1]
        if (x <= t1) {
            val f = if (t1 == t0) 0f else (x - t0) / (t1 - t0)
            return lerpColor(c0, c1, f)
        }
    }
    return plasmaStops.last().second
}

private fun lerpColor(a: Color, b: Color, f: Float): Color = Color(
    red = a.red + (b.red - a.red) * f,
    green = a.green + (b.green - a.green) * f,
    blue = a.blue + (b.blue - a.blue) * f,
    alpha = 1f,
)

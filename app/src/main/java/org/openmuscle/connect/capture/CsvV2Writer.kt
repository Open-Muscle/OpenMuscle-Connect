package org.openmuscle.connect.capture

import org.openmuscle.connect.protocol.MatrixUtil
import java.io.Writer

/**
 * Writes a multi-device capture in schema v2 (docs/CSV-SCHEMA-V2.md): one row per
 * source frame, columns `ts_hub_ms, role, device_id, R{r}C{c}..., label_0..M`.
 *
 * Byte-compatible with the canonical golden in tools/make_golden_csv_v2.py (CRLF,
 * row-major `R{r}C{c}` sensor names, epoch-ms `ts_hub_ms`, float labels), so the
 * phone and PC v2 writers stay byte-identical. The header is written lazily on the
 * first row so the label count can come from the first matched label, mirroring the
 * v1 writer and the PC `CaptureWriter`.
 *
 * Sensor values arrive already flattened row-major (via [MatrixUtil.flattenRowMajor]);
 * labels are floats (LASK5 calibrated `[0,1]`). One writer serves a whole capture,
 * including multiple sources: each row carries its own `role` + `device_id`.
 */
class CsvV2Writer(
    private val out: Writer,
    private val rows: Int,
    private val cols: Int,
    private val labelCount: Int? = null,
) {
    private var headerWritten = false

    var rowCount: Long = 0
        private set

    fun writeRow(
        tsHubMs: Long,
        role: String,
        deviceId: String,
        sensorValues: IntArray,
        labelValues: List<Double>,
    ) {
        if (!headerWritten) writeHeader(labelCount ?: labelValues.size)
        val sb = StringBuilder()
        sb.append(tsHubMs).append(',').append(role).append(',').append(deviceId)
        for (v in sensorValues) {
            sb.append(',').append(v)
        }
        for (v in labelValues) {
            sb.append(',').append(v)
        }
        sb.append(CRLF)
        out.write(sb.toString())
        rowCount++
    }

    private fun writeHeader(count: Int) {
        val sb = StringBuilder("ts_hub_ms,role,device_id")
        for (name in MatrixUtil.sensorColumnNames(rows, cols)) {
            sb.append(',').append(name)
        }
        for (i in 0 until count) {
            sb.append(",label_").append(i)
        }
        sb.append(CRLF)
        out.write(sb.toString())
        headerWritten = true
    }

    fun flush() = out.flush()

    /** Emits a header even for an empty session, so consumers never hit a 0-byte file. */
    fun close() {
        if (!headerWritten) writeHeader(labelCount ?: 0)
        out.flush()
        out.close()
    }

    private companion object {
        const val CRLF = "\r\n"
    }
}

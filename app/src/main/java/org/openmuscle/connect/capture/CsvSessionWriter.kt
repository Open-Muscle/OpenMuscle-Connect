package org.openmuscle.connect.capture

import org.openmuscle.connect.protocol.MatrixUtil
import java.io.Writer

/**
 * Writes a capture session in the exact CSV the PC trainer reads
 * (docs/WIRE-FORMAT.md section 5). Byte-compatible with the PC CaptureWriter,
 * INCLUDING the CRLF line terminator Python's csv module emits by default;
 * verified against a golden literal generated from the real PC writer
 * (tools/make_golden_csv.py and CsvSessionWriterTest).
 *
 * The header is written lazily on the first row so the label count can come from
 * the first matched label, mirroring the PC writer. Pass [labelCount] when known
 * up front, or null to infer from the first row.
 */
class CsvSessionWriter(
    private val out: Writer,
    private val rows: Int,
    private val cols: Int,
    private val labelCount: Int? = 4,
) {
    private var headerWritten = false

    var rowCount: Long = 0
        private set

    fun writeRow(timestamp: Long, sensorValues: IntArray, labelValues: List<Double>) {
        if (!headerWritten) writeHeader(labelCount ?: labelValues.size)
        val sb = StringBuilder()
        sb.append(timestamp)
        for (v in sensorValues) {
            sb.append(',').append(v)
        }
        for (v in labelValues) {
            // Label floats use Double.toString, which matches Python's str(float)
            // shortest-round-trip output for the [0,1] LASK5 range, keeping the
            // CSV byte-compatible with the PC CaptureWriter (csv.writer -> str()).
            sb.append(',').append(v)
        }
        sb.append(CRLF)
        out.write(sb.toString())
        rowCount++
    }

    private fun writeHeader(count: Int) {
        val sb = StringBuilder("timestamp")
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

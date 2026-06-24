package org.openmuscle.connect.capture

import android.content.Context
import java.io.File

/** Metadata for one recorded session file. */
data class SessionInfo(
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

/**
 * Manages capture CSV files under `filesDir/sessions/`. Files are app-private;
 * sharing them out goes through a FileProvider (see MainActivity.shareSession).
 */
class SessionStore(private val context: Context) {

    private fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** A fresh file for a new recording, named by start time. */
    fun newSessionFile(): File =
        File(dir(), "capture_${System.currentTimeMillis()}.csv")

    fun list(): List<SessionInfo> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { SessionInfo(it.name, it.length(), it.lastModified()) }
            ?: emptyList()

    fun file(name: String): File = File(dir(), name)

    fun delete(name: String): Boolean = File(dir(), name).delete()

    private companion object {
        const val DIR = "sessions"
    }
}

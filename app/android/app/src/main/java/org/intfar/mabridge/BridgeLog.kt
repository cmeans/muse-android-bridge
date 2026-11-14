package org.intfar.mabridge

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The on-phone log, bridge.log (PROTOCOL §5.6). The service and the setup screen both
 * append to it, so one lock serializes their writes within the app's process.
 */
object BridgeLog {
    const val FILE = "bridge.log"
    private val lock = Any()

    fun append(ctx: Context, text: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        Log.i("MABridge", text)
        synchronized(lock) {
            try {
                File(ctx.filesDir, FILE).appendText("$ts | $text\n")
            } catch (e: Exception) {
                Log.w("MABridge", "couldn't append to $FILE", e)
            }
        }
    }

    /**
     * The last [n] lines, oldest first; empty when nothing was logged yet. Reads at most
     * [maxBytes] from the end of the file, so the cost doesn't grow with the log (it has no
     * cap yet); a line cut by that window is dropped.
     */
    fun lastLines(ctx: Context, n: Int, maxBytes: Int = 256 * 1024): List<String> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        java.io.RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val start = maxOf(0L, len - maxBytes)
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
            return (if (start > 0) lines.drop(1) else lines).takeLast(n)
        }
    }
}

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

    /** The last [n] lines, oldest first; empty when nothing was logged yet. */
    fun lastLines(ctx: Context, n: Int): List<String> {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) f.readLines().takeLast(n) else emptyList()
    }
}

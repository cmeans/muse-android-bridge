package org.intfar.mabridge

import org.intfar.mabridge.protocol.SeenStore
import org.intfar.mabridge.protocol.pruneSeen
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * The dedup window kept in [file], so it survives service restarts and
 * reboots. One line per ID: base64url(id) and the epoch ms it was seen.
 * Encoding the ID means a hostile ID can't add lines.
 *
 * A new ID appends one line, so a burst of messages costs one short write
 * each, not a rewrite of the whole window. Once the file holds more than
 * twice the live entries (expired ones included), it's rewritten with just
 * the live ones, through a temporary file and a rename.
 *
 * [onError] hears about a failed read or write. A failed read starts the
 * window empty rather than stopping the service; a failed write keeps the
 * in-memory entry, so the message still runs once in this process and only
 * restart survival is lost.
 */
class FileSeenStore(
    private val file: File,
    private val onError: (Exception) -> Unit = {},
) : SeenStore {
    private val seen = HashMap<String, Long>()
    private var linesInFile = 0

    init {
        try {
            if (file.exists()) {
                file.readLines().forEach { line ->
                    linesInFile++
                    val parts = line.split(' ')
                    if (parts.size != 2) return@forEach
                    val ts = parts[1].toLongOrNull() ?: return@forEach
                    val id = try {
                        String(DECODER.decode(parts[0]), Charsets.UTF_8)
                    } catch (_: IllegalArgumentException) {
                        return@forEach
                    }
                    seen[id] = maxOf(ts, seen[id] ?: ts)
                }
            }
        } catch (e: Exception) {
            seen.clear()
            linesInFile = 0
            onError(e)
        }
    }

    @Synchronized
    override fun markIfNew(id: String, nowMs: Long): Boolean {
        pruneSeen(seen, nowMs)
        if (id in seen) return false
        seen[id] = nowMs
        try {
            if (linesInFile + 1 > 2 * seen.size + COMPACT_SLACK) compact() else append(id, nowMs)
        } catch (e: Exception) {
            onError(e)
        }
        return true
    }

    private fun line(id: String, ts: Long) = "${ENCODER.encodeToString(id.toByteArray(Charsets.UTF_8))} $ts\n"

    private fun append(id: String, ts: Long) {
        FileOutputStream(file, true).use { it.write(line(id, ts).toByteArray(Charsets.UTF_8)) }
        linesInFile++
    }

    private fun compact() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(seen.entries.joinToString("") { (k, v) -> line(k, v) })
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
        }
        linesInFile = seen.size
    }

    private companion object {
        /** Lines allowed beyond twice the live entries before a compaction, so a small window isn't rewritten every time. */
        const val COMPACT_SLACK = 8
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}

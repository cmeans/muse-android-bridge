package org.intfar.mabridge

import org.intfar.mabridge.protocol.SeenStore
import org.intfar.mabridge.protocol.pruneSeen
import java.io.File
import java.util.Base64

/**
 * The dedup window kept in [file], so it survives service restarts and
 * reboots. One line per ID: base64url(id) and the epoch ms it was seen.
 * Encoding the ID means a hostile ID can't add lines. Each change rewrites
 * the file through a temporary file and a rename.
 */
class FileSeenStore(private val file: File) : SeenStore {
    private val seen = HashMap<String, Long>()

    init {
        if (file.exists()) {
            file.readLines().forEach { line ->
                val parts = line.split(' ')
                if (parts.size != 2) return@forEach
                val ts = parts[1].toLongOrNull() ?: return@forEach
                val id = try {
                    String(DECODER.decode(parts[0]), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    return@forEach
                }
                seen[id] = ts
            }
        }
    }

    @Synchronized
    override fun markIfNew(id: String, nowMs: Long): Boolean {
        pruneSeen(seen, nowMs)
        if (id in seen) return false
        seen[id] = nowMs
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(seen.entries.joinToString("") { (k, v) -> "${ENCODER.encodeToString(k.toByteArray(Charsets.UTF_8))} $v\n" })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        return true
    }

    private companion object {
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}

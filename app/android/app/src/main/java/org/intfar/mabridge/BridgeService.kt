package org.intfar.mabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The executor: subscribes to both command topics over ntfy's streaming
 * JSON endpoint, applies PROTOCOL.md in order (STOP gates -> TTL -> dedup
 * -> exact-match parse -> announce -> act -> ack -> log). Plain
 * HttpURLConnection, no external dependencies.
 */
class BridgeService : Service(), TextToSpeech.OnInitListener {

    @Volatile private var running = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val seenLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithStatus("Listening on both channels")
        if (!running) {
            running = true
            thread(name = "mab-owner") { subscribeLoop(Config.ownerTopic(this), family = false) }
            thread(name = "mab-family") { subscribeLoop(Config.familyTopic(this), family = true) }
            log("service started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        tts?.shutdown()
        super.onDestroy()
    }

    // ---- subscription -------------------------------------------------

    private fun subscribeLoop(topic: String, family: Boolean) {
        var backoffMs = 2_000L
        while (running) {
            try {
                val url = URL("${Config.server(this)}/$topic/json")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", basicAuth())
                conn.connectTimeout = 15_000
                conn.readTimeout = 0 // streaming
                conn.connect()
                if (conn.responseCode != 200) {
                    log("subscribe $topic -> HTTP ${conn.responseCode}")
                    conn.disconnect()
                    Thread.sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000)
                    continue
                }
                backoffMs = 2_000L
                log("subscribed $topic")
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) handleLine(line, family)
                }
                conn.disconnect()
            } catch (e: Exception) {
                log("subscribe $topic error: ${e.message}")
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { }
                backoffMs = (backoffMs * 2).coerceAtMost(60_000)
            }
        }
    }

    private fun basicAuth(): String {
        val raw = "${Config.username(this)}:${Config.password(this)}"
        return "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    }

    // ---- protocol -----------------------------------------------------

    private fun handleLine(line: String, family: Boolean) {
        val msg = try { JSONObject(line) } catch (e: Exception) { return }
        if (msg.optString("event") != "message") return
        val id = msg.optString("id")
        val time = msg.optLong("time")
        val body = msg.optString("message").trim()
        val topic = msg.optString("topic")

        // Gate 1: STOP switches (PROTOCOL section 6)
        if (Config.stopAll(this)) { log("drop (STOP all) $id $body"); return }
        if (family && Config.stopFamily(this)) { log("drop (STOP family) $id $body"); return }

        // Gate 2: TTL (section 2.3)
        val nowSec = System.currentTimeMillis() / 1000
        if (nowSec - time > Config.TTL_SECONDS) {
            ack("err stale $id"); log("drop stale $id age=${nowSec - time}s"); return
        }

        // Gate 3: dedup by message id, 24h window (section 2.3)
        if (!markSeen(id)) { log("drop duplicate $id"); return }

        // Gate 4: exact-match parse, never interpret (section 2.1/2.2)
        execute(body, family, id, topic)
    }

    private fun execute(body: String, family: Boolean, id: String, topic: String) {
        val launchMatch = Regex("^launch ([a-z]+)$").matchEntire(body)
        when {
            launchMatch != null -> {
                val app = launchMatch.groupValues[1]
                val entry = Config.REGISTRY[app]
                if (entry == null) { ack("err unknown-command $id"); log("unknown app $app"); return }
                announce("Opening ${entry.first}")
                val intent = packageManager.getLaunchIntentForPackage(entry.second)
                if (intent == null) {
                    ack("err not-installed $app"); log("not installed: $app"); return
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                ack("ok launch $app")
                log("ok launch $app (from $topic)")
            }
            body == "ring find" -> {
                announce("Ringing so you can find the phone")
                ringPhone()
                ack("ok ring find")
                log("ok ring find (from $topic)")
            }
            body == "status report" && !family -> {
                val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ack("ok status battery=$level stopAll=${Config.stopAll(this)} stopFamily=${Config.stopFamily(this)}")
                log("ok status report")
            }
            body == "bootstrap rustdesk" || body == "update rustdesk" -> {
                if (family) { ack("err unknown-command $id"); return }
                // Phase 3 stub: never pretend a control capability exists.
                ack("err not-implemented $id")
                log("not-implemented: $body")
            }
            else -> {
                ack("err unknown-command $id")
                log("unknown command: $body (from $topic)")
            }
        }
    }

    // ---- actions --------------------------------------------------------

    private fun announce(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "mab")
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureAnnounceChannel(nm)
        val n = Notification.Builder(this, CH_ANNOUNCE)
            .setContentTitle("Muse Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    private fun ringPhone() {
        thread(name = "mab-ring") {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val rt = RingtoneManager.getRingtone(this, uri)
            rt.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            rt.play()
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && !Config.stopAll(this)) {
                Thread.sleep(500)
            }
            rt.stop()
        }
    }

    private fun ack(text: String) {
        thread(name = "mab-ack") {
            try {
                val url = URL("${Config.server(this)}/")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", basicAuth())
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = JSONObject()
                    .put("topic", Config.ackTopic(this))
                    .put("message", text)
                    .toString()
                conn.outputStream.use { it.write(payload.toByteArray()) }
                conn.responseCode // drain
                conn.disconnect()
            } catch (e: Exception) {
                log("ack failed: ${e.message}")
            }
        }
    }

    // ---- bookkeeping ----------------------------------------------------

    private fun markSeen(id: String): Boolean {
        if (id.isBlank()) return true
        val f = File(filesDir, "seen_ids.txt")
        synchronized(seenLock) {
            val now = System.currentTimeMillis()
            val kept = mutableListOf<String>()
            var dup = false
            if (f.exists()) {
                f.readLines().forEach { line ->
                    val parts = line.split(" ")
                    if (parts.size == 2) {
                        val ts = parts[1].toLongOrNull() ?: 0
                        if (now - ts < Config.DEDUP_WINDOW_MS) {
                            kept.add(line)
                            if (parts[0] == id) dup = true
                        }
                    }
                }
            }
            if (dup) return false
            kept.add("$id $now")
            f.writeText(kept.joinToString("\n") + "\n")
            return true
        }
    }

    fun log(text: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        Log.i("MABridge", text)
        try {
            File(filesDir, "bridge.log").appendText("$ts | $text\n")
        } catch (_: Exception) { }
    }

    // ---- foreground plumbing ---------------------------------------------

    private fun startForegroundWithStatus(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureStatusChannel(nm)
        val n = Notification.Builder(this, CH_STATUS)
            .setContentTitle("Muse Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
    }

    private fun ensureStatusChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Bridge status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun ensureAnnounceChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CH_ANNOUNCE, "Bridge announcements", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        private const val CH_STATUS = "mab_status"
        private const val CH_ANNOUNCE = "mab_announce"
    }
}

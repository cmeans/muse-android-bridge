package org.intfar.mabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import org.intfar.mabridge.protocol.Backoff
import org.intfar.mabridge.protocol.Channel
import org.intfar.mabridge.protocol.Command
import org.intfar.mabridge.protocol.Decision
import org.intfar.mabridge.protocol.Gate
import org.intfar.mabridge.protocol.PILOT_REGISTRY
import org.intfar.mabridge.protocol.Receipts
import org.intfar.mabridge.protocol.StopState
import org.intfar.mabridge.protocol.parseNtfyLine
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The executor's I/O. One ntfy streaming connection carries both command
 * topics; every line goes through parseNtfyLine and Gate.decide (all
 * protocol decisions live there), and this class only carries out the
 * Decision: announce, launch or ring, post the receipt, append to the log.
 */
class BridgeService : Service(), TextToSpeech.OnInitListener {

    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var ringtone: Ringtone? = null
    @Volatile private var ringGeneration = 0
    private var savedAlarmVolume: Int? = null
    private lateinit var gate: Gate
    private lateinit var acks: ExecutorService
    private val logLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        acks = Executors.newSingleThreadExecutor()
        gate = Gate(
            slug = Config.deviceSlug(this),
            registry = PILOT_REGISTRY,
            seen = FileSeenStore(File(filesDir, "seen_ids.txt")) { log("dedup file write failed: ${it.javaClass.simpleName}") },
            clockMs = System::currentTimeMillis,
            stop = { StopState(all = Config.stopAll(this), family = Config.stopFamily(this)) },
        )
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithStatus("Listening for commands")
        if (intent?.action == ACTION_STOP_RING) {
            stopRinging()
            return START_STICKY
        }
        val missing = Config.missingSetup(this)
        if (missing.isNotEmpty()) {
            log("not starting: setup incomplete (${missing.joinToString(", ")})")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            Config.prefs(this).edit().putBoolean("service_running", true).apply()
            thread(name = "mab-subscribe") { subscribeLoop() }
            log("service started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Config.prefs(this).edit().putBoolean("service_running", false).apply()
        conn?.disconnect()
        stopRinging()
        tts?.shutdown()
        acks.shutdown()
        log("service stopped")
        super.onDestroy()
    }

    /** Android 15+ calls this when a foreground service type's time limit is reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        log("foreground time limit reached (type $fgsType); stopping")
        stopSelf()
    }

    // ---- subscription -----------------------------------------------------

    private fun subscribeLoop() {
        val backoff = Backoff()
        val slug = Config.deviceSlug(this)
        val topics = "${Channel.OWNER.topic(slug)},${Channel.FAMILY.topic(slug)}"
        while (running) {
            var c: HttpURLConnection? = null
            try {
                // Catch up from the last message seen, so a command sent during a
                // reconnect gap still arrives; the gate drops anything stale (120 s)
                // or already seen. With no message seen yet, look back 2 minutes.
                val since = Config.prefs(this).getString(PREF_LAST_ID, null)
                    ?.let { URLEncoder.encode(it, "UTF-8") } ?: "2m"
                c = URL("${Config.server(this)}/$topics/json?since=$since").openConnection() as HttpURLConnection
                conn = c
                c.setRequestProperty("Authorization", basicAuth())
                c.connectTimeout = 15_000
                // ntfy sends a keepalive about every 45 s: a silent stream for this
                // long is a dead (half-open) connection, so reconnect.
                c.readTimeout = STREAM_READ_TIMEOUT_MS
                val code = c.responseCode
                if (code != 200) {
                    log("subscribe: HTTP $code")
                } else {
                    Config.prefs(this).edit().putLong("last_connected", System.currentTimeMillis()).apply()
                    log("subscribed")
                    var first = true
                    c.inputStream.bufferedReader().use { reader ->
                        while (running) {
                            val line = reader.readLine() ?: break
                            // Reset only once the stream delivers something, so a server
                            // that answers 200 and closes at once still backs off.
                            if (first) { backoff.reset(); first = false }
                            if (running && line.isNotBlank()) handle(line)
                        }
                    }
                    if (running) log("stream ended")
                }
            } catch (e: Exception) {
                if (running) log("subscribe error: ${e.javaClass.simpleName}")
            } finally {
                c?.disconnect()
            }
            if (running) {
                try { Thread.sleep(backoff.nextDelayMs()) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun basicAuth(): String {
        val pw = CredentialStore(this).load() ?: CharArray(0)
        val raw = "${Config.username(this)}:${String(pw)}"
        pw.fill('\u0000')
        return "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    }

    // ---- protocol -----------------------------------------------------------

    private fun handle(line: String) {
        val msg = parseNtfyLine(line) ?: return // keepalive, open, or not a usable message
        Config.prefs(this).edit().putString(PREF_LAST_ID, msg.id).apply()
        when (val d = gate.decide(msg)) {
            is Decision.Ignore -> log(d.log)
            is Decision.DropDuplicate -> log(d.log)
            is Decision.DropStale -> { log(d.log); ack(d.receipt) }
            is Decision.Reject -> { log(d.log); ack(d.receipt) }
            is Decision.Run -> try {
                run(d.command, d.channel)
            } catch (e: Exception) {
                // One failed action mustn't drop the subscription; it isn't retried (§5).
                ack(Receipts.failed(d.command))
                log("failed (${d.channel.name.lowercase()}): ${d.command.text}: ${e.javaClass.simpleName}")
            }
        }
    }

    /** False when the announcement banner can't show (notifications off for the app or its channel). */
    private fun canAnnounce(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureAnnounceChannel(nm)
        val ch = nm.getNotificationChannel(CH_ANNOUNCE)
        return nm.areNotificationsEnabled() && ch != null && ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun run(command: Command, channel: Channel) {
        val who = if (channel == Channel.OWNER) "the owner channel" else "family"
        val name = channel.name.lowercase()
        when (command) {
            is Command.Launch -> {
                val app = PILOT_REGISTRY.apps.getValue(command.app)
                val intent = packageManager.getLaunchIntentForPackage(app.pkg)
                if (intent == null) {
                    ack(Receipts.notInstalled(command))
                    log("not-installed ($name): ${command.text}")
                    return
                }
                if (!canAnnounce()) {
                    ack(Receipts.cannotAnnounce(command))
                    log("cannot-announce ($name): ${command.text}: notifications are off")
                    return
                }
                if (!Settings.canDrawOverlays(this)) {
                    // Android blocks activity starts from the background without it.
                    log("warning: 'Display over other apps' is off; the launch may be blocked")
                }
                announce("Opening ${app.displayName}: asked by $who")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                ack(Receipts.ok(command))
                log("ok ($name): ${command.text}")
            }
            Command.RingFind -> {
                if (!canAnnounce()) {
                    ack(Receipts.cannotAnnounce(command))
                    log("cannot-announce ($name): ${command.text}: notifications are off")
                    return
                }
                announce("Ringing so you can find the phone: asked by $who")
                if (startRing(channel)) {
                    ack(Receipts.ok(command))
                    log("ok ($name): ${command.text}")
                } else {
                    ack(Receipts.failed(command))
                    log("failed ($name): ${command.text}: no alarm or ringtone sound")
                }
            }
            Command.StatusReport -> {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                val stop = StopState(all = Config.stopAll(this), family = Config.stopFamily(this))
                ack(Receipts.status(battery, version, PROTOCOL_VERSION, stop))
                log("ok ($name): ${command.text}")
            }
            Command.BootstrapRustdesk, Command.UpdateRustdesk -> {
                ack(Receipts.notImplemented(command))
                log("not-implemented ($name): ${command.text}")
            }
        }
    }

    // ---- actions ------------------------------------------------------------

    private fun announce(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "mab")
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureAnnounceChannel(nm)
        val n = Notification.Builder(this, CH_ANNOUNCE)
            .setContentTitle("Muse Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100_000).toInt() + 10, n)
    }

    private fun ensureAnnounceChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CH_ANNOUNCE, "Bridge announcements", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /**
     * Rings at full alarm volume, looping, for 30 s or until Stop ringing, STOP all,
     * or (for a family ring) STOP family. False if the phone has no sound to play.
     * Each ring has a generation number, so a superseded ring always stops.
     */
    @Synchronized
    private fun startRing(channel: Channel): Boolean {
        stopRinging()
        val rt = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            ?: RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ?: return false
        rt.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        rt.isLooping = true
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: Exception) {
            savedAlarmVolume = null
            log("ring: couldn't raise the alarm volume: ${e.javaClass.simpleName}")
        }
        rt.play()
        ringtone = rt
        val gen = ++ringGeneration

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureAnnounceChannel(nm)
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            RING_NOTIFICATION,
            Notification.Builder(this, CH_ANNOUNCE)
                .setContentTitle("Muse Bridge is ringing")
                .setContentText("Tap Stop ringing to silence it")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .addAction(Notification.Action.Builder(null, "Stop ringing", stopIntent).build())
                .setOngoing(true)
                .build(),
        )
        thread(name = "mab-ring") {
            val until = System.currentTimeMillis() + RING_MS
            while (ringGeneration == gen && System.currentTimeMillis() < until &&
                !Config.stopAll(this) && !(channel == Channel.FAMILY && Config.stopFamily(this))
            ) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
            synchronized(this) { if (ringGeneration == gen) stopRinging() }
        }
        return true
    }

    @Synchronized
    private fun stopRinging() {
        ringGeneration++
        ringtone?.stop()
        ringtone = null
        savedAlarmVolume?.let { v ->
            try {
                (getSystemService(AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, v, 0)
            } catch (_: Exception) { }
        }
        savedAlarmVolume = null
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(RING_NOTIFICATION)
    }

    private fun ack(text: String) {
        if (acks.isShutdown) return
        acks.execute {
            try {
                val c = URL("${Config.server(this)}/").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.setRequestProperty("Authorization", basicAuth())
                c.setRequestProperty("Content-Type", "application/json")
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                c.doOutput = true
                val payload = JSONObject().put("topic", Config.ackTopic(this)).put("message", text).toString()
                c.outputStream.use { it.write(payload.toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                if (code != 200) log("receipt failed: HTTP $code: $text")
            } catch (e: Exception) {
                log("receipt failed: ${e.javaClass.simpleName}: $text")
            }
        }
    }

    // ---- bookkeeping --------------------------------------------------------

    private fun log(text: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        Log.i("MABridge", text)
        synchronized(logLock) {
            try {
                File(filesDir, LOG_FILE).appendText("$ts | $text\n")
            } catch (_: Exception) { }
        }
    }

    private fun startForegroundWithStatus(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Bridge status", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CH_STATUS)
            .setContentTitle("Muse Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_NOTIFICATION, n)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "0.2"
        const val LOG_FILE = "bridge.log"
        private const val ACTION_STOP_RING = "org.intfar.mabridge.STOP_RING"
        private const val CH_STATUS = "mab_status"
        private const val CH_ANNOUNCE = "mab_announce"
        private const val STATUS_NOTIFICATION = 1
        private const val RING_NOTIFICATION = 2
        private const val RING_MS = 30_000L
        private const val PREF_LAST_ID = "last_message_id"
        private const val STREAM_READ_TIMEOUT_MS = 120_000
    }
}

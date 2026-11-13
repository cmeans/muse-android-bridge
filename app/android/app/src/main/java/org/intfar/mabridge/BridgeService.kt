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
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.SystemClock
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
import org.intfar.mabridge.protocol.RateLimiter
import org.intfar.mabridge.protocol.StaleReceipts
import org.intfar.mabridge.protocol.Receipts
import org.intfar.mabridge.protocol.STOP_PREF_KEYS
import org.intfar.mabridge.protocol.StopButton
import org.intfar.mabridge.protocol.StopState
import org.intfar.mabridge.protocol.statusNotificationFor
import org.intfar.mabridge.protocol.stopTapFor
import org.intfar.mabridge.protocol.parseNtfyLine
import org.intfar.mabridge.protocol.parseNtfyOpenTime
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
 * topics; every line goes through parseNtfyLine and Gate.decide (every gate
 * decision: topic, TTL, dedup, registry, STOP), and this class carries out the
 * Decision: announce, launch or ring, post the receipt, append to the log. The
 * action-time receipts (not-installed, cannot-announce, cannot-launch, blocked,
 * failed, rate-limited) are decided here and are covered by the device tests;
 * the rate limit and stale cap logic (RateLimiter, StaleReceipts) by JVM tests.
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
    private val limiter = RateLimiter()
    private val stale = StaleReceipts()
    private lateinit var acks: ExecutorService
    private val logLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    // Re-posts the status notification whenever a STOP switch changes, from the owner screen
    // or the notification itself, so the notification always matches. A field: preferences
    // hold their listeners weakly.
    private val stopListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in STOP_PREF_KEYS) startForegroundWithStatus()
    }

    override fun onCreate() {
        super.onCreate()
        Config.prefs(this).registerOnSharedPreferenceChangeListener(stopListener)
        // The status notification moved to a new channel (see startForegroundWithStatus); the
        // old silent one goes once, here.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).deleteNotificationChannel(CH_STATUS_OLD)
        tts = TextToSpeech(this, this)
        acks = Executors.newSingleThreadExecutor()
        gate = Gate(
            slug = Config.deviceSlug(this),
            registry = PILOT_REGISTRY,
            seen = FileSeenStore(File(filesDir, "seen_ids.txt")) { log("dedup file read or write failed: ${it.javaClass.simpleName}") },
            clockMs = System::currentTimeMillis,
            stop = ::stopState,
        )
    }

    private fun stopState() = StopState(all = Config.stopAll(this), family = Config.stopFamily(this))

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The STOP buttons on the status notification: local and owner-only, like the
        // switches on the owner screen (PROTOCOL §7). Turning them off again is done there.
        val tap = stopTapFor(intent?.action, stopState())
        if (tap != null) {
            // Logged first: the owner screen re-reads the log when the switch changes.
            log(tap.logLine)
            Config.prefs(this).edit().putBoolean(tap.button.prefKey, true).apply()
            if (tap.stopsRinging) stopRinging(tap.button.switchName)
        }
        startForegroundWithStatus()
        // Since Android 14 the owner can swipe the status notification away; it comes straight
        // back, so the STOP buttons stay one tap away.
        if (tap != null || intent?.action == ACTION_REPOST_STATUS) {
            if (!running) stopSelf()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP_RING) {
            stopRinging("Stop ringing tapped")
            // A leftover ring notification can start a service that isn't running.
            if (!running) stopSelf()
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
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(stopListener)
        val wasRunning = running
        running = false
        flushBacklog()
        Config.prefs(this).edit().putBoolean("service_running", false).apply()
        conn?.disconnect()
        stopRinging("bridge stopped")
        tts?.shutdown()
        acks.shutdown()
        if (wasRunning) log("service stopped")
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
                    val openedAt = SystemClock.elapsedRealtime() // a duration: immune to clock changes
                    try {
                        c.inputStream.bufferedReader().use { reader ->
                            while (running) {
                                val line = reader.readLine() ?: break
                                if (running && line.isNotBlank()) handle(line)
                            }
                        }
                    } finally {
                        backoff.streamEnded(SystemClock.elapsedRealtime() - openedAt)
                        flushBacklog()
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
        parseNtfyOpenTime(line)?.let { openTime ->
            // A new subscription: cached messages older than this are a replayed backlog (§6).
            flushBacklog()
            stale.connected(openTime)
            return
        }
        val msg = parseNtfyLine(line) ?: return // keepalive, or not a usable message
        Config.prefs(this).edit().putString(PREF_LAST_ID, msg.id).apply()
        when (val d = gate.decide(msg)) {
            is Decision.Ignore -> log(d.log)
            is Decision.DropDuplicate -> log(d.log)
            is Decision.DropStale -> {
                log(d.log)
                // A long outage can replay many cached messages: cap their receipts so the
                // receipt for a fresh command isn't queued behind them all (§6).
                if (stale.shouldSend(msg.timeSec)) ack(d.receipt)
            }
            is Decision.Reject -> { flushBacklog(); log(d.log); ack(d.receipt) }
            is Decision.Run -> try {
                flushBacklog()
                // Monotonic: a wall-clock correction mustn't strand the limiter's times (§3).
                val now = SystemClock.elapsedRealtime()
                if (!limiter.allows(d.channel, d.command, now)) {
                    // One receipt per channel and kind per window; a looping publisher's
                    // repeats are logged only, so they can't fill the receipt queue (§6).
                    val receipt = limiter.shouldReceiptRefusal(d.channel, d.command, now)
                    if (receipt) ack(Receipts.rateLimited(d.command))
                    log("rate-limited (${d.channel.name.lowercase()}): ${d.command.text}${if (receipt) "" else " (no receipt: one already sent this window)"}")
                    return
                }
                // Every command past STOP counts, whatever it then does (§3, §6).
                limiter.record(d.channel, d.command, now)
                run(d.command, d.channel)
            } catch (e: Exception) {
                // One failed action mustn't drop the subscription; it isn't retried (§5).
                ack(Receipts.failed(d.command))
                log("failed (${d.channel.name.lowercase()}): ${d.command.text}: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The replayed backlog is over: log how many stale receipts it left unsent (#8). */
    private fun flushBacklog() {
        stale.flush().let { if (it > 0) log("stale backlog: $it dropped-stale receipts not sent") }
    }

    /** Whether the owner gave the app usage access, which the launch check needs. */
    private fun hasUsageAccess(): Boolean = Config.hasUsageAccess(this)

    /**
     * Whether [pkg] is in front within FOREGROUND_CHECK_MS of [sinceMs] (#7): the most recent
     * activity Android resumed, in the usage events it records, belongs to [pkg]. That also
     * covers an app that was already in front, which a launch just brings forward without a
     * new resume. Null when it can't tell: no usage access, no app resumed in the last
     * 24 h, the check itself failed, or the bridge is stopping. The log says which.
     */
    private fun reachedForeground(pkg: String, sinceMs: Long): Boolean? {
        if (!hasUsageAccess()) return null
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val deadline = SystemClock.elapsedRealtime() + FOREGROUND_CHECK_MS
        fun lastResumed(fromMs: Long, toMs: Long): String? {
            val events = usm.queryEvents(fromMs, toMs)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
            }
            return last
        }
        try {
            // Which app was in front before the launch: read once, over a long window, so an
            // app on screen for hours (a long call, a phone left locked) still counts.
            val before = lastResumed(sinceMs - FOREGROUND_LOOKBACK_MS, sinceMs - 1_000)
            while (true) {
                val front = lastResumed(sinceMs - 1_000, System.currentTimeMillis() + 1_000) ?: before
                if (front == pkg) return true
                if (!running) return null // stopping: don't hold up onDestroy
                // No app resumed at all in the last 24 h (the phone sat idle that long, or
                // keeps no usage history): the check can't tell, so unverified, not blocked.
                if (SystemClock.elapsedRealtime() >= deadline) {
                    if (front == null) log("launch check: no app resumed in 24 h; unverified")
                    return if (front == null) null else false
                }
                Thread.sleep(250)
            }
        } catch (e: Exception) {
            // The launch was sent; a failed check makes it unverified, not failed.
            log("launch check failed: ${e.javaClass.simpleName}")
            return null
        }
    }

    /** False when the announcement banner can't show (notifications off for the app or its channel). */
    private fun canAnnounce(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureAnnounceChannel(nm)
        val ch = nm.getNotificationChannel(CH_ANNOUNCE)
        return nm.areNotificationsEnabled() && ch != null && ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Carries out a command that passed the gate and the rate limit, and posts its receipt. */
    private fun run(command: Command, channel: Channel) {
        val who = if (channel == Channel.OWNER) "the owner channel" else "family"
        val name = channel.name.lowercase()
        // Launch and ring announce first; without a visible banner they don't run (§1).
        if ((command is Command.Launch || command == Command.RingFind) && !canAnnounce()) {
            // A launch of an app that isn't installed still reports not-installed first.
            val notInstalled = command is Command.Launch &&
                packageManager.getLaunchIntentForPackage(PILOT_REGISTRY.apps.getValue(command.app).pkg) == null
            if (!notInstalled) {
                ack(Receipts.cannotAnnounce(command))
                log("cannot-announce ($name): ${command.text}: notifications are off")
                return
            }
        }
        when (command) {
            is Command.Launch -> {
                val app = PILOT_REGISTRY.apps.getValue(command.app)
                val intent = packageManager.getLaunchIntentForPackage(app.pkg)
                if (intent == null) {
                    ack(Receipts.notInstalled(command))
                    log("not-installed ($name): ${command.text}")
                    return
                }
                if (!Settings.canDrawOverlays(this)) {
                    // Android blocks activity starts from the background without it (#7).
                    ack(Receipts.cannotLaunch(command))
                    log("cannot-launch ($name): ${command.text}: 'Display over other apps' is off")
                    return
                }
                announce("Opening ${app.displayName}: asked by $who")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val startedAt = System.currentTimeMillis()
                startActivity(intent)
                when (reachedForeground(app.pkg, startedAt)) {
                    false -> {
                        ack(Receipts.blocked(command))
                        log("blocked ($name): ${command.text}: didn't come to the front within ${FOREGROUND_CHECK_MS / 1000} s")
                    }
                    true -> {
                        ack(Receipts.ok(command))
                        log("ok ($name): ${command.text}")
                    }
                    null -> {
                        ack(Receipts.ok(command))
                        log("ok ($name): ${command.text} (unverified${if (hasUsageAccess()) "" else ": no usage access"})")
                    }
                }
                return
            }
            Command.RingFind -> {
                announce("Ringing so you can find the phone: asked by $who")
                if (startRing(channel)) {
                    ack(Receipts.ok(command))
                    log("ok ($name): ${command.text}")
                } else {
                    ack(Receipts.failed(command))
                    log("failed ($name): ${command.text}: no sound played")
                }
            }
            Command.StatusReport -> {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                ack(Receipts.status(battery, version, PROTOCOL_VERSION, stopState(), launchCheck = hasUsageAccess()))
                log("ok ($name): ${command.text}")
                return
            }
            Command.BootstrapRustdesk, Command.UpdateRustdesk -> {
                ack(Receipts.notImplemented(command))
                log("not-implemented ($name): ${command.text}")
                return
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
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentIntent(ownerScreen())
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
     * or (for a family ring) STOP family. False if no sound started playing.
     * A `ring find` while one is ringing keeps the current ring and its end time
     * (true; it still counted toward the rate limit, like every ring find past STOP).
     * A new ring is true only once the ringtone player reports playing, which is as close to
     * "audible" as the app can check (Do Not Disturb or OEM audio policy may
     * still silence it). The play check waits outside the service's lock, so a
     * Stop ringing tap isn't held up by it.
     */
    private fun startRing(channel: Channel): Boolean {
        val start = beginRing(channel) ?: return false
        if (start.alreadyRinging) return true
        val deadline = SystemClock.elapsedRealtime() + PLAY_CHECK_MS
        var sawPlaying = start.rt.isPlaying
        while (!sawPlaying && ringGeneration == start.gen && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
            sawPlaying = start.rt.isPlaying
        }
        if (sawPlaying) return true
        // Never reported playing: stop it if it's still this ring, and send `failed`
        // either way, even if Stop ringing ended it during the check.
        synchronized(this) { if (ringGeneration == start.gen) stopRinging("no sound started") }
        return false
    }

    private class RingStart(val rt: Ringtone, val gen: Int, val alreadyRinging: Boolean = false)

    /** Sets up and plays a ring, or reports the one already ringing; null if there's no sound to play. */
    @Synchronized
    private fun beginRing(channel: Channel): RingStart? {
        ringtone?.let { current ->
            log("ring find: already ringing; the current ring keeps its end time")
            return RingStart(current, ringGeneration, alreadyRinging = true)
        }
        val rt = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            ?: RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ?: return null
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
        ringtone = rt
        val gen = ++ringGeneration
        try {
            armRing(rt, gen, channel)
        } catch (e: Exception) {
            stopRinging("error")
            throw e
        }
        return RingStart(rt, gen)
    }

    /** Posts Stop ringing, starts the watcher that ends the ring, then plays: in that order. */
    private fun armRing(rt: Ringtone, gen: Int, channel: Channel) {
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
                .setSmallIcon(R.drawable.ic_bridge)
                .addAction(Notification.Action.Builder(null, "Stop ringing", stopIntent).build())
                // Its own group, so Stop ringing isn't bundled behind the announcement before it.
                .setGroup(GROUP_RING)
                .setContentIntent(ownerScreen())
                // Stop ringing must work from the lock screen, whatever it hides.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .build(),
        )
        thread(name = "mab-ring") {
            val until = SystemClock.elapsedRealtime() + RING_MS // a clock change can't stretch the ring
            var reason = "30 s elapsed"
            while (ringGeneration == gen) {
                if (SystemClock.elapsedRealtime() >= until) break
                if (Config.stopAll(this)) { reason = "STOP all"; break }
                if (channel == Channel.FAMILY && Config.stopFamily(this)) { reason = "STOP family"; break }
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
            synchronized(this) { if (ringGeneration == gen) stopRinging(reason) }
        }
        // Started last, once the notification and the watcher that stop it exist.
        rt.play()
    }

    @Synchronized
    private fun stopRinging(reason: String? = null) {
        ringGeneration++
        if (ringtone != null && reason != null) log("ring ended: $reason")
        ringtone?.stop()
        ringtone = null
        savedAlarmVolume?.let { v ->
            try {
                (getSystemService(AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, v, 0)
            } catch (e: Exception) {
                Log.w("MABridge", "couldn't restore the alarm volume", e)
                log("ring: couldn't restore the alarm volume to $v: ${e.javaClass.simpleName}")
            }
        }
        savedAlarmVolume = null
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(RING_NOTIFICATION)
    }

    private fun ack(text: String) {
        if (acks.isShutdown) {
            // The service is stopping (e.g. during a launch check): say so, don't drop it silently.
            log("receipt not sent, bridge stopping: $text")
            return
        }
        try {
            acks.execute {
            var c: HttpURLConnection? = null
            try {
                c = URL("${Config.server(this)}/").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.setRequestProperty("Authorization", basicAuth())
                c.setRequestProperty("Content-Type", "application/json")
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                c.doOutput = true
                val payload = JSONObject().put("topic", Config.ackTopic(this)).put("message", text).toString()
                c.outputStream.use { it.write(payload.toByteArray()) }
                val code = c.responseCode
                if (code != 200) log("receipt failed: HTTP $code: $text")
            } catch (e: Exception) {
                log("receipt failed: ${e.javaClass.simpleName}: $text")
            } finally {
                c?.disconnect()
            }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shut down between the check above and here.
            log("receipt not sent, bridge stopping: $text")
        }
    }

    // ---- bookkeeping --------------------------------------------------------

    private fun log(text: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        Log.i("MABridge", text)
        synchronized(logLock) {
            try {
                File(filesDir, LOG_FILE).appendText("$ts | $text\n")
            } catch (e: Exception) {
                Log.w("MABridge", "couldn't append to $LOG_FILE", e)
            }
        }
    }

    /** Opens the owner screen: tapping a bridge notification brings the app to the front. */
    private fun ownerScreen(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, BridgeService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The ongoing status notification: what the bridge is doing, a tap to open the owner
     * screen, and one-tap STOP buttons for whichever channels are still on.
     */
    private fun startForegroundWithStatus() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // Default importance, with no sound or vibration: lock screens hide silent (low)
        // notifications by default, and the STOP buttons must show there. A channel's
        // importance can't be raised once created, so this is a new channel; onCreate deletes
        // the old silent one.
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Bridge status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val status = statusNotificationFor(stopState())
        val b = Notification.Builder(this, CH_STATUS)
            .setContentTitle("Muse Bridge")
            .setContentText(status.text)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentIntent(ownerScreen())
            .setDeleteIntent(serviceAction(ACTION_REPOST_STATUS, 4))
            // Nothing private in it, and the STOP buttons must show on the lock screen even
            // when the phone hides sensitive notification content there.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Re-posted on every STOP change; only the first post may alert.
            .setOnlyAlertOnce(true)
            // Its own group, so Android doesn't bundle it with announcements, which would hide
            // the STOP buttons behind the bundle.
            .setGroup(GROUP_STATUS)
        for (button in status.buttons) {
            // Request codes 2 and 3: distinct from Stop ringing (1) and the repost (4).
            val pending = serviceAction(button.action, 2 + button.ordinal)
            b.addAction(Notification.Action.Builder(null, button.label, pending).build())
        }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_NOTIFICATION, n)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "0.3"
        const val LOG_FILE = "bridge.log"
        private const val ACTION_STOP_RING = "org.intfar.mabridge.STOP_RING"
        private const val ACTION_REPOST_STATUS = "org.intfar.mabridge.REPOST_STATUS"
        private const val CH_STATUS = "mab_status_v2"
        /** The silent status channel used before mab_status_v2; lock screens hide it by default. */
        private const val CH_STATUS_OLD = "mab_status"
        private const val CH_ANNOUNCE = "mab_announce"
        private const val GROUP_STATUS = "mab_status"
        private const val GROUP_RING = "mab_ring"
        private const val STATUS_NOTIFICATION = 1
        private const val RING_NOTIFICATION = 2
        private const val RING_MS = 30_000L
        /** How long a launched app has to come to the front before the launch counts as blocked. */
        private const val FOREGROUND_CHECK_MS = 5_000L
        /** How far back the launch check reads usage events, to see an app that was already in front. */
        private const val FOREGROUND_LOOKBACK_MS = 24 * 60 * 60_000L
        /** How long a ring may take to start playing before it counts as failed. */
        private const val PLAY_CHECK_MS = 1_000L
        private const val PREF_LAST_ID = "last_message_id"
        private const val STREAM_READ_TIMEOUT_MS = 120_000
    }
}

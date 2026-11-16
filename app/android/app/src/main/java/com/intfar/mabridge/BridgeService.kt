package com.intfar.mabridge

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.intfar.mabridge.protocol.Backoff
import com.intfar.mabridge.protocol.CONFIRM_TIMEOUT_MS
import com.intfar.mabridge.protocol.CREDENTIAL_UNREADABLE
import com.intfar.mabridge.protocol.Channel
import com.intfar.mabridge.protocol.Command
import com.intfar.mabridge.protocol.ConfirmEvent
import com.intfar.mabridge.protocol.ConfirmOutcome
import com.intfar.mabridge.protocol.ConfirmRequest
import com.intfar.mabridge.protocol.Decision
import com.intfar.mabridge.protocol.FamilyMember
import com.intfar.mabridge.protocol.Gate
import com.intfar.mabridge.protocol.PILOT_REGISTRY
import com.intfar.mabridge.protocol.PROBE_EVERY_MS
import com.intfar.mabridge.protocol.PROTOCOL_VERSION
import com.intfar.mabridge.protocol.ProbeRound
import com.intfar.mabridge.protocol.PendingConfirm
import com.intfar.mabridge.protocol.RateLimiter
import com.intfar.mabridge.protocol.StaleReceipts
import com.intfar.mabridge.protocol.Receipts
import com.intfar.mabridge.protocol.STOP_PREF_KEYS
import com.intfar.mabridge.protocol.Source
import com.intfar.mabridge.protocol.StopButton
import com.intfar.mabridge.protocol.StopState
import com.intfar.mabridge.protocol.askedBy
import com.intfar.mabridge.protocol.confirmOutcome
import com.intfar.mabridge.protocol.confirmPromptText
import com.intfar.mabridge.protocol.confirmPromptTitle
import com.intfar.mabridge.protocol.droppedMemberLog
import com.intfar.mabridge.protocol.effectiveEvent
import com.intfar.mabridge.protocol.hostOf
import com.intfar.mabridge.protocol.isExpired
import com.intfar.mabridge.protocol.memberTopic
import com.intfar.mabridge.protocol.needsConfirm
import com.intfar.mabridge.protocol.confirmRequestFor
import com.intfar.mabridge.protocol.refusedForLog
import com.intfar.mabridge.protocol.rustdeskRequestAnnouncement
import com.intfar.mabridge.protocol.subscribeTopics
import com.intfar.mabridge.protocol.endedByBridge
import com.intfar.mabridge.protocol.isReprobeDue
import com.intfar.mabridge.protocol.probeRound
import com.intfar.mabridge.protocol.reprobeDueAt
import com.intfar.mabridge.protocol.logTag
import com.intfar.mabridge.protocol.isValidMessageId
import com.intfar.mabridge.protocol.sinceParam
import com.intfar.mabridge.protocol.statusNotificationFor
import com.intfar.mabridge.protocol.stopTapFor
import com.intfar.mabridge.protocol.parseNtfyLine
import com.intfar.mabridge.protocol.parseNtfyOpenTime
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The executor's I/O. One ntfy streaming connection carries the owner topic and
 * each family member's topic (any the server refuses are left out, design §2); every line goes through parseNtfyLine and Gate.decide (every gate
 * decision: topic, TTL, dedup, registry, STOP), and this class carries out the
 * Decision: announce, launch or ring, post the receipt, append to the log. The
 * action-time receipts (not-installed, cannot-announce, cannot-launch, blocked,
 * failed, rate-limited) are decided here and are covered by the device tests;
 * the rate limit and stale cap logic (RateLimiter, StaleReceipts) by JVM tests.
 * A family member's `launch rustdesk` waits for the owner's tap on a confirm prompt first
 * (design §4; the decisions are RustdeskConfirm's, JVM-tested).
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
    /** Runs Open RustDesk taps: the launch check sleeps up to 5 s, so never on the main thread. */
    private lateinit var confirms: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    /**
     * Held from reading the pending confirm request to clearing it, by every path that answers
     * it, so two callers can't both answer it. Never taken while holding [runLock]. The setup
     * screen holds it too ([WaitingConfirm.lock]), when a save answers the request.
     */
    private val confirmLock = WaitingConfirm.lock
    /** Held by every [run] call, so two launches can't overlap (the launch check reads the front app). */
    private val runLock = Any()
    /**
     * The stream the bridge ended itself, to reconnect with a newly readable member topic
     * ([endedByBridge]): tied to that stream, so a probe that hit an already-closed one can't
     * mute the next stream's "stream ended" or skip its backoff.
     */
    @Volatile private var reconnectTarget: HttpURLConnection? = null
    /** Held while arming, firing or clearing the re-probe ([probeDueAt], [probeTick]), so only one tick is ever armed. */
    private val probeLock = Any()
    /** When the refused member topics are next asked about, on elapsedRealtime; null when none are refused. */
    private var probeDueAt: Long? = null
    /** The re-probe's Handler timer. It counts uptime, so [handle] checks the deadline too. */
    private val probeTick = Runnable { probeIfDue() }

    override fun onBind(intent: Intent?): IBinder? = null

    // Re-posts the status notification whenever a STOP switch changes, from the owner screen
    // or the notification itself, so the notification always matches. A field: preferences
    // hold their listeners weakly. Turning on STOP all or STOP family also withdraws a waiting
    // confirm prompt (design §4), but only while the bridge runs: a STOP tap on a stopped bridge
    // leaves the request to be answered at the next start.
    private val stopListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in STOP_PREF_KEYS) {
            startForegroundWithStatus()
            val stop = stopState()
            if (running && (stop.all || stop.family)) resolveConfirm(ConfirmEvent.STOPPED, null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Config.prefs(this).registerOnSharedPreferenceChangeListener(stopListener)
        // The status notification moved to a new channel (see startForegroundWithStatus); the
        // old silent one goes once, here.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).deleteNotificationChannel(CH_STATUS_OLD)
        tts = TextToSpeech(this, this)
        acks = Executors.newSingleThreadExecutor()
        confirms = Executors.newSingleThreadExecutor()
        gate = Gate(
            slug = Config.deviceSlug(this),
            registry = PILOT_REGISTRY,
            seen = FileSeenStore(File(filesDir, "seen_ids.txt")) { log("dedup file read or write failed: ${it.javaClass.simpleName}") },
            clockMs = System::currentTimeMillis,
            stop = ::stopState,
            members = { Config.familyMembers(this) },
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
        // back, so the STOP buttons stay on the notification.
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
        if (intent?.action == ACTION_CONFIRM_OPEN || intent?.action == ACTION_CONFIRM_DECLINE) {
            // A tap on a stopped bridge (the owner's Stop bridge) does nothing, like the STOP
            // taps: the stored request is answered as RESTARTED at the next real start.
            if (!running) {
                stopSelf()
                return START_STICKY
            }
            // No id never matches a pending request, so the tap counts as on an ended one.
            val id = intent.getStringExtra(EXTRA_CONFIRM_ID) ?: ""
            if (intent.action == ACTION_CONFIRM_DECLINE) {
                if (resolveConfirm(ConfirmEvent.NOT_NOW, id) != ConfirmEvent.NOT_NOW) requestEnded()
            } else {
                openConfirmed(id)
            }
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
            // A fresh start reads every member topic again; the subscribe thread refills this.
            setRefused(emptyList())
            Config.prefs(this).edit().putBoolean("service_running", true).apply()
            log("service started")
            // A stored member line the bridge can't use reads no topic: say so once per start (slugs only).
            for (d in Config.droppedMembers(this)) log(droppedMemberLog(d))
            // A request left waiting by a restart, a reboot, a setup save or a Stop bridge gets
            // its one receipt now (design §4), before subscribing. A save that changed the server
            // or device name has answered it already, on the old ones (SetupActivity.apply).
            resolveConfirm(ConfirmEvent.RESTARTED, null)
            thread(name = "mab-subscribe") { subscribeLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(stopListener)
        val wasRunning = running
        running = false
        flushBacklog()
        // A waiting request stays stored (answered as RESTARTED at the next start); only its
        // timer and prompt go. Under confirmLock, after running is false: an askConfirm still
        // posting either finishes first (and this removes what it posted) or sees the stop and
        // removes them itself. Holds no runLock (lock order). The refused list is this run's.
        synchronized(confirmLock) { cancelConfirmPrompt() }
        cancelReprobe()
        Config.prefs(this).edit().remove(Config.PREF_REFUSED_MEMBERS).putBoolean("service_running", false).apply()
        conn?.disconnect()
        stopRinging("bridge stopped")
        tts?.shutdown()
        acks.shutdown()
        confirms.shutdown()
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
        val owner = Channel.OWNER.topic(slug)
        // The last probe round found every topic readable and reconnected at once (ProbeRound).
        var allReadableBefore = false
        while (running) {
            reconnectTarget = null
            val retriedAllReadable = allReadableBefore
            allReadableBefore = false
            // Set when this connect ends in an at-once reconnect: no backoff, no "ended" line.
            var reconnect = false
            // Setup saves restart the service, but reading the list here costs nothing.
            val members = Config.familyMembers(this)
            val topics = subscribeTopics(slug, members)
            var c: HttpURLConnection? = null
            try {
                // Read once per connect, for the stream and its probes: never an empty password.
                val auth = basicAuth() ?: throw CredentialUnreadableException()
                c = openStream(topics, auth)
                var code = c.responseCode
                var refused = emptyList<String>()
                // The probe round wrote this connect's line itself (ProbeRound.AllReadable).
                var roundLogged = false
                if (code == 403 && topics.size > 1) {
                    // ntfy refuses the whole list when any one topic is unreadable, and doesn't
                    // say which (design §2): ask about each alone, the owner topic included.
                    c.disconnect()
                    val probed = LinkedHashMap<String, Int>()
                    // The owner topic comes first: a network error on its probe is thrown to the
                    // catch below, so it logs today's `subscribe error` and backs off.
                    probed[owner] = probe(owner, auth, throwErrors = true)
                    for (t in topics) {
                        if (!running) break
                        if (t != owner) probed[t] = probe(t, auth)
                    }
                    if (!running) break
                    when (val round = probeRound(owner, slug, members.map { it.slug }, probed, retriedAllReadable)) {
                        is ProbeRound.Partial -> {
                            refused = round.refused
                            val answers = refused.map { it to probed[memberTopic(slug, it)] }
                            log("subscribe: HTTP 403; family topics refused: ${refusedForLog(answers)}")
                            c = openStream(round.topics, auth)
                            code = c.responseCode
                        }
                        // A refused owner topic keeps today's `subscribe: HTTP 403` and backoff;
                        // any other answer is logged as itself, so the log names the real cause.
                        is ProbeRound.OwnerRefused -> code = round.code
                        // Access was granted between the subscribe and the probes: not a refusal.
                        is ProbeRound.AllReadable -> {
                            roundLogged = true
                            if (round.reconnectNow) {
                                log("subscribe: HTTP 403, but every topic now answers; reconnecting")
                                allReadableBefore = true
                                reconnect = true
                            } else {
                                log("subscribe: HTTP 403, though every topic answers alone; retrying later")
                            }
                        }
                    }
                }
                if (code != 200) {
                    if (!roundLogged) log("subscribe: HTTP $code")
                } else {
                    setRefused(refused)
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
                    // The bridge ending its own stream to pick up a member topic isn't news.
                    if (endedByBridge(reconnectTarget, c)) reconnect = true
                    else if (running) log("stream ended")
                }
            } catch (_: CredentialUnreadableException) {
                // Backs off like any failed connect, one line each time.
                if (running) log("$CREDENTIAL_UNREADABLE; not subscribing")
            } catch (e: Exception) {
                if (endedByBridge(reconnectTarget, c)) reconnect = true
                else if (running) log("subscribe error: ${e.javaClass.simpleName}")
            } finally {
                c?.disconnect()
            }
            if (running && !reconnect) {
                try { Thread.sleep(backoff.nextDelayMs()) } catch (_: InterruptedException) { }
            }
        }
    }

    /**
     * Opens the stream for [topics] with [auth], the Authorization header; [conn] tracks it, so
     * stopping the bridge can end it.
     */
    private fun openStream(topics: List<String>, auth: String): HttpURLConnection {
        // Catch up from the last message seen, so a command sent during a
        // reconnect gap still arrives; the gate drops anything stale (120 s)
        // or already seen. With no message seen yet, look back 2 minutes.
        // sinceParam passes only a 12-character [A-Za-z0-9] id, so nothing needs encoding.
        val since = sinceParam(Config.prefs(this).getString(PREF_LAST_ID, null))
        val c = URL("${Config.server(this)}/${topics.joinToString(",")}/json?since=$since").openConnection() as HttpURLConnection
        conn = c
        c.setRequestProperty("Authorization", auth)
        c.connectTimeout = 15_000
        // ntfy sends a keepalive about every 45 s: a silent stream for this
        // long is a dead (half-open) connection, so reconnect.
        c.readTimeout = STREAM_READ_TIMEOUT_MS
        return c
    }

    /**
     * Whether the phone's credential can read [topic] alone: a one-shot poll, answered 200
     * (readable) or 403 (refused) at once. -1 for a network error, which, like any status but
     * 200, counts as refused for this round (the log names which, [refusedForLog]); with
     * [throwErrors] the error is thrown instead. Short timeouts: it holds up a reconnect, or the
     * receipt queue when the 15-minute timer runs it. [auth] is the Authorization header.
     */
    private fun probe(topic: String, auth: String, throwErrors: Boolean = false): Int {
        var c: HttpURLConnection? = null
        return try {
            c = URL("${Config.server(this)}/$topic/json?poll=1&since=none").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", auth)
            c.connectTimeout = PROBE_TIMEOUT_MS
            c.readTimeout = PROBE_TIMEOUT_MS
            c.responseCode
        } catch (e: Exception) {
            if (throwErrors) throw e
            -1
        } finally {
            c?.disconnect()
        }
    }

    /**
     * Records the refused member slugs: in a preference, so the owner screen's Family tile
     * follows (it reads service state only from preferences), and on the status notification.
     * While any are refused, they are asked about again in 15 minutes: the deadline is on
     * elapsedRealtime, checked by the Handler tick and by every line read ([probeIfDue]). The one
     * place the re-probe is armed, so two ticks can't be.
     */
    private fun setRefused(slugs: List<String>) {
        if (slugs != Config.refusedMembers(this)) {
            val edit = Config.prefs(this).edit()
            if (slugs.isEmpty()) edit.remove(Config.PREF_REFUSED_MEMBERS)
            else edit.putString(Config.PREF_REFUSED_MEMBERS, slugs.joinToString(","))
            edit.apply()
            startForegroundWithStatus()
        }
        synchronized(probeLock) {
            main.removeCallbacks(probeTick)
            probeDueAt = reprobeDueAt(slugs, SystemClock.elapsedRealtime())
            if (probeDueAt != null) main.postDelayed(probeTick, PROBE_EVERY_MS)
        }
    }

    /** Clears the re-probe: the bridge is stopping. */
    private fun cancelReprobe() {
        synchronized(probeLock) {
            main.removeCallbacks(probeTick)
            probeDueAt = null
        }
    }

    /**
     * Runs the re-probe, once, when its deadline has passed: from the Handler tick, which counts
     * uptime and so stops in deep sleep, and from every line read (keepalives about every 45 s),
     * which catches up after it. Disarms it first; [probeRefused] re-arms it through [setRefused].
     */
    private fun probeIfDue() {
        synchronized(probeLock) {
            val dueAt = probeDueAt ?: return
            val now = SystemClock.elapsedRealtime()
            if (!isReprobeDue(dueAt, now)) return
            probeDueAt = null
            main.removeCallbacks(probeTick)
        }
        try {
            if (running && !acks.isShutdown) acks.execute { probeRefused() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shut down between the check and here: the bridge is stopping.
        }
    }

    /**
     * The 15-minute re-probe, on the [acks] executor: asks about the refused member topics
     * only. When one now answers 200, ends the current stream, so the loop reconnects with it
     * (and re-arms through [setRefused] once subscribed); otherwise it logs nothing, keeps the
     * stream, so Recent doesn't fill with "Connected", and re-arms through [setRefused].
     */
    private fun probeRefused() {
        if (!running) return
        val slug = Config.deviceSlug(this)
        val refused = Config.refusedMembers(this)
        if (refused.isEmpty()) return
        val auth = basicAuth()
        // Re-arms with the list as stored now, not as read above: a reconnect during the probes
        // may have recorded a newer one, which mustn't be overwritten.
        if (auth == null) {
            // One line per round, and re-armed: re-entering the password restarts the bridge.
            log("$CREDENTIAL_UNREADABLE; family topics not asked about")
            setRefused(Config.refusedMembers(this))
            return
        }
        val readable = refused.filter { running && probe(memberTopic(slug, it), auth) == 200 }
        if (!running) return
        if (readable.isEmpty()) {
            setRefused(Config.refusedMembers(this))
            return
        }
        log("family topics now readable: ${readable.joinToString(", ") { "family/$it" }}; reconnecting")
        val target = conn
        reconnectTarget = target
        target?.disconnect()
    }

    /** The Authorization header, or null when the stored password can't be decrypted. */
    private fun basicAuth(): String? = basicAuth(this)

    // ---- protocol -----------------------------------------------------------

    private fun handle(line: String) {
        // The confirm timer stops in deep sleep: every line read, keepalives included (about
        // every 45 s), checks the waiting request's deadline too (design §4), and the re-probe's.
        expireConfirm()
        probeIfDue()
        parseNtfyOpenTime(line)?.let { openTime ->
            // A new subscription: cached messages older than this are a replayed backlog (§6).
            flushBacklog()
            stale.connected(openTime)
            return
        }
        val msg = parseNtfyLine(line) ?: return // keepalive, or not a usable message
        // An id the gate ignores mustn't become the reconnect's since= (0.4 id grammar).
        if (isValidMessageId(msg.id)) Config.prefs(this).edit().putString(PREF_LAST_ID, msg.id).apply()
        when (val d = gate.decide(msg)) {
            is Decision.Ignore -> log(d.log)
            is Decision.DropDuplicate -> log(d.log)
            is Decision.DropStale -> {
                log(d.log)
                // A long outage can replay many cached messages: cap their receipts so the
                // receipt for a fresh command isn't queued behind them all (§6).
                if (stale.shouldSend(msg.timeSec)) ack(d.receipt, d.id)
            }
            is Decision.Reject -> { flushBacklog(); log(d.log); ack(d.receipt, d.id) }
            is Decision.Run -> try {
                flushBacklog()
                // Monotonic: a wall-clock correction mustn't strand the limiter's times (§3).
                val now = SystemClock.elapsedRealtime()
                if (!limiter.allows(d.source.channel, d.command, now)) {
                    // One receipt per channel and kind per window; a looping publisher's
                    // repeats are logged only, so they can't fill the receipt queue (§6).
                    val receipt = limiter.shouldReceiptRefusal(d.source.channel, d.command, now)
                    if (receipt) ack(Receipts.rateLimited(d.command), msg.id)
                    log("rate-limited (${d.source.logTag()}): ${d.command.text}${if (receipt) "" else " (no receipt: one already sent this window)"}")
                    return
                }
                // Every command past STOP counts, whatever it then does (§3, §6).
                limiter.record(d.source.channel, d.command, now)
                // A family member's RustDesk launch waits for the owner's tap (design §4).
                if (needsConfirm(d.command, d.source)) askConfirm(d.command as Command.Launch, d.source, msg.id)
                else synchronized(runLock) { run(d.command, d.source, msg.id) }
            } catch (e: Exception) {
                // One failed action mustn't drop the subscription; it isn't retried (§5).
                ack(Receipts.failed(d.command), msg.id)
                log("failed (${d.source.logTag()}): ${d.command.text}: ${e.javaClass.simpleName}")
            }
        }
    }

    // ---- confirm tap (design §4) ---------------------------------------------

    /**
     * A family member's `launch rustdesk`: today's refusals first, with today's receipts, then
     * one prompt for the owner, or `pending` while another request waits. Called on the
     * subscribe thread, inside handle's try, so an exception still gets its one receipt.
     */
    private fun askConfirm(command: Command.Launch, source: Source, id: String) {
        val member = checkNotNull(source.member) { "a family source names its member" }
        val tag = source.logTag()
        val app = PILOT_REGISTRY.apps.getValue(command.app)
        if (packageManager.getLaunchIntentForPackage(app.pkg) == null) {
            ack(Receipts.notInstalled(command), id)
            log("not-installed ($tag): ${command.text}")
            return
        }
        if (!canAnnounce()) {
            ack(Receipts.cannotAnnounce(command), id)
            log("cannot-announce ($tag): ${command.text}: notifications are off")
            return
        }
        if (!canAskConfirm()) {
            // The prompt would never show, so the request could only time out.
            ack(Receipts.cannotAnnounce(command), id)
            log("cannot-announce ($tag): ${command.text} (help requests off)")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            ack(Receipts.cannotLaunch(command), id)
            log("cannot-launch ($tag): ${command.text}: 'Display over other apps' is off")
            return
        }
        val now = SystemClock.elapsedRealtime()
        // Reentrant: the expired request's TIMEOUT answers inside this lock, and never launches.
        synchronized(confirmLock) {
            readPending()?.let { if (isExpired(it, now)) resolveConfirm(ConfirmEvent.TIMEOUT, it.id) }
            // STOP is read again here (the gate read it before): a STOP switched on since then
            // has had its listener's STOPPED find nothing stored, so it's answered now; one
            // switched on after this lock is released finds the stored request and withdraws it.
            val p = when (val r = confirmRequestFor(stopState(), readPending(), id, member.slug, now)) {
                ConfirmRequest.Disabled -> {
                    ack(Receipts.disabled(command), id)
                    log("disabled ($tag): ${command.text}")
                    return
                }
                ConfirmRequest.Busy -> {
                    ack(Receipts.pending(command), id)
                    log("pending ($tag): ${command.text}")
                    return
                }
                is ConfirmRequest.Ask -> r.pending
            }
            storePending(p)
            try {
                announce(rustdeskRequestAnnouncement(member.name))
                log("confirm-asked ($tag): ${command.text}")
                postConfirmPrompt(p, member.name)
                // A stopped bridge's timer does nothing (resolveConfirm): the request waits for
                // RESTARTED at the next start.
                main.postDelayed({ if (running) resolveConfirm(ConfirmEvent.TIMEOUT, p.id) }, CONFIRM_TOKEN, CONFIRM_TIMEOUT_MS)
            } catch (e: Exception) {
                // handle's catch sends `failed`: the request mustn't also time out into a second receipt.
                endPending()
                throw e
            }
            // The bridge stopped while this posted: onDestroy may have cleared the prompt and
            // timer before they existed. The request stays stored for RESTARTED; they go.
            if (!running) cancelConfirmPrompt()
        }
    }

    /** Answers the waiting request as TIMEOUT when its deadline has passed. */
    private fun expireConfirm() {
        val p = readPending() ?: return
        if (isExpired(p, SystemClock.elapsedRealtime())) resolveConfirm(ConfirmEvent.TIMEOUT, p.id)
    }

    /**
     * The one way a waiting request ends: reads it, decides with confirmOutcome and clears it
     * under [confirmLock], then answers or launches outside it. [eventId] is the request the
     * event is about (a tap's id); null means whichever is stored (timer-less events: STOPPED,
     * RESTARTED). A tap (OPEN, NOT_NOW) past the deadline counts as TIMEOUT (effectiveEvent):
     * the Handler timer stops in deep sleep and an unlock can run past the deadline. Returns
     * the event the request was resolved as, or null when nothing was resolved: no request, or
     * another one than [eventId]. A launch (OPEN only) takes [runLock] after [confirmLock] is
     * released, never inside it. On a stopped bridge nothing resolves (null): a TIMEOUT's receipt
     * could no longer be sent, and an OPEN racing onDestroy would launch with its receipt
     * dropped. `running` is read under [confirmLock], which onDestroy takes after clearing it,
     * so the stored request stays for RESTARTED at the next start.
     */
    private fun resolveConfirm(event: ConfirmEvent, eventId: String?): ConfirmEvent? {
        val (pending, resolvedAs, outcome) = synchronized(confirmLock) {
            if (!running) return null
            val p = readPending() ?: return null
            val e = effectiveEvent(p, event, SystemClock.elapsedRealtime())
            val o = confirmOutcome(p, e, eventId ?: p.id) ?: return null
            endPending()
            Triple(p, e, o)
        }
        when (outcome) {
            is ConfirmOutcome.Answer -> {
                ack(outcome.receipt, pending.id)
                log(outcome.log)
            }
            ConfirmOutcome.Launch -> launchConfirmed(pending)
        }
        return resolvedAs
    }

    /** Open RustDesk, on the [confirms] executor: the launch path waits up to 5 s for its check. */
    private fun openConfirmed(id: String) {
        try {
            confirms.execute {
                // shutdown() still runs queued tasks. A bridge stopped before the tap is resolved
                // never launches: resolveConfirm reads `running` under the confirm lock, and the
                // stored request stays for RESTARTED at the next start. One stopped after that,
                // during the 5 s launch check, has launched already; its receipt is then logged as
                // not sent (ack).
                if (!running) return@execute
                // Belt and braces: STOP withdraws the request itself, but nothing launches under STOP.
                val stop = stopState()
                val event = if (stop.all || stop.family) ConfirmEvent.STOPPED else ConfirmEvent.OPEN
                // Ended already, or ended now because the tap came past the deadline. A stop in
                // between resolves nothing, and the request hasn't ended: no word then.
                if (resolveConfirm(event, id) != event && running) requestEnded()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The bridge is stopping; the request is answered at the next start.
        }
    }

    /** Today's launch path for the confirmed request, in today's order, with handle's catch. */
    private fun launchConfirmed(p: PendingConfirm) {
        // A setup save restarts a running bridge (RESTARTED), so the member is normally still set up.
        val member = Config.familyMembers(this).firstOrNull { it.slug == p.member } ?: FamilyMember(p.member, "a family member")
        val source = Source(Channel.FAMILY, member)
        synchronized(runLock) {
            try {
                run(RUSTDESK, source, p.id)
            } catch (e: Exception) {
                ack(Receipts.failed(RUSTDESK), p.id)
                log("failed (${source.logTag()}): ${RUSTDESK.text}: ${e.javaClass.simpleName}")
            }
        }
    }

    /** A tap on a request that has ended: nothing runs; a short word so the owner isn't left wondering. */
    private fun requestEnded() {
        main.post { Toast.makeText(this, "That request has ended", Toast.LENGTH_SHORT).show() }
    }

    private fun readPending(): PendingConfirm? = WaitingConfirm.read(this)

    private fun storePending(p: PendingConfirm) = WaitingConfirm.store(this, p)

    /** Clears the stored request, its timer and its prompt. Called holding [confirmLock]. */
    private fun endPending() {
        WaitingConfirm.clear(this)
        cancelConfirmPrompt()
    }

    /** Removes the confirm timer and prompt, keeping any stored request. Called holding [confirmLock]. */
    private fun cancelConfirmPrompt() {
        main.removeCallbacksAndMessages(CONFIRM_TOKEN)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(CONFIRM_NOTIFICATION)
    }

    /** False when the "Help requests" channel is off, so a confirm prompt would never show. */
    private fun canAskConfirm(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureConfirmChannel(nm)
        val ch = nm.getNotificationChannel(CH_CONFIRM)
        return nm.areNotificationsEnabled() && ch != null && ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun ensureConfirmChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CH_CONFIRM, "Help requests", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /**
     * The owner's prompt: Open RustDesk (an activity, so the phone must be unlocked) or Not now.
     * No content intent: only the two buttons answer it. Android matches PendingIntents without
     * their extras, so each intent carries the request id as its identifier too, or a later
     * prompt's buttons could carry an earlier id.
     */
    @Suppress("DEPRECATION") // setPriority: channels replace it from API 26; kept for older-style shades
    private fun postConfirmPrompt(p: PendingConfirm, name: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureConfirmChannel(nm)
        val open = PendingIntent.getActivity(
            this, 5,
            Intent(this, ConfirmActivity::class.java)
                .putExtra(EXTRA_CONFIRM_ID, p.id)
                .setIdentifier(p.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getService(
            this, 6,
            Intent(this, BridgeService::class.java)
                .setAction(ACTION_CONFIRM_DECLINE)
                .putExtra(EXTRA_CONFIRM_ID, p.id)
                .setIdentifier(p.id),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openAction = Notification.Action.Builder(null, "Open RustDesk", open).apply {
            if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true)
        }.build()
        val n = Notification.Builder(this, CH_CONFIRM)
            .setContentTitle(confirmPromptTitle(name))
            .setContentText(confirmPromptText(name))
            .setSmallIcon(R.drawable.ic_bridge)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)
            // Only removes the prompt; the bridge's own deadline sends the answer.
            .setTimeoutAfter(CONFIRM_TIMEOUT_MS)
            .addAction(openAction)
            .addAction(Notification.Action.Builder(null, "Not now", decline).build())
            .setGroup(GROUP_CONFIRM)
            .build()
        nm.notify(CONFIRM_NOTIFICATION, n)
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

    /**
     * Carries out a command that passed the gate and the rate limit, and posts its receipt,
     * which names [id], the command message's id (0.4). Callers hold [runLock].
     */
    private fun run(command: Command, source: Source, id: String) {
        // The owner channel is the owner's own Muse, a family command names its member: the
        // announcement says so in the owner's words. The log names the slug, never the name.
        val who = askedBy(source)
        val name = source.logTag()
        // Launch and ring announce first; without a visible banner they don't run (§1).
        if ((command is Command.Launch || command == Command.RingFind) && !canAnnounce()) {
            // A launch of an app that isn't installed still reports not-installed first.
            val notInstalled = command is Command.Launch &&
                packageManager.getLaunchIntentForPackage(PILOT_REGISTRY.apps.getValue(command.app).pkg) == null
            if (!notInstalled) {
                ack(Receipts.cannotAnnounce(command), id)
                log("cannot-announce ($name): ${command.text}: notifications are off")
                return
            }
        }
        when (command) {
            is Command.Launch -> {
                val app = PILOT_REGISTRY.apps.getValue(command.app)
                val intent = packageManager.getLaunchIntentForPackage(app.pkg)
                if (intent == null) {
                    ack(Receipts.notInstalled(command), id)
                    log("not-installed ($name): ${command.text}")
                    return
                }
                if (!Settings.canDrawOverlays(this)) {
                    // Android blocks activity starts from the background without it (#7).
                    ack(Receipts.cannotLaunch(command), id)
                    log("cannot-launch ($name): ${command.text}: 'Display over other apps' is off")
                    return
                }
                announce("Opening ${app.displayName}: asked by $who")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val startedAt = System.currentTimeMillis()
                startActivity(intent)
                when (reachedForeground(app.pkg, startedAt)) {
                    false -> {
                        ack(Receipts.blocked(command), id)
                        log("blocked ($name): ${command.text}: didn't come to the front within ${FOREGROUND_CHECK_MS / 1000} s")
                    }
                    true -> {
                        ack(Receipts.ok(command), id)
                        log("ok ($name): ${command.text}")
                    }
                    null -> {
                        ack(Receipts.ok(command), id)
                        log("ok ($name): ${command.text} (unverified${if (hasUsageAccess()) "" else ": no usage access"})")
                    }
                }
                return
            }
            Command.RingFind -> {
                announce("Ringing so you can find the phone: asked by $who")
                if (startRing(source.channel)) {
                    ack(Receipts.ok(command), id)
                    log("ok ($name): ${command.text}")
                } else {
                    ack(Receipts.failed(command), id)
                    log("failed ($name): ${command.text}: no sound played")
                }
            }
            Command.StatusReport -> {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                // The wall clock, the one the freshness check reads (§2.3), not elapsedRealtime.
                ack(Receipts.status(battery, version, PROTOCOL_VERSION, stopState(), launchCheck = hasUsageAccess(), nowMs = System.currentTimeMillis()), id)
                log("ok ($name): ${command.text}")
                return
            }
            Command.BootstrapRustdesk, Command.UpdateRustdesk -> {
                ack(Receipts.notImplemented(command), id)
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

    /**
     * Posts [body] with [id], the id of the command message it answers, appended (0.4): a
     * required parameter, so no receipt can be sent without it. Log lines show the full text.
     */
    private fun ack(body: String, id: String) {
        val text = Receipts.withId(body, id)
        if (acks.isShutdown) {
            // The service is stopping (e.g. during a launch check): say so, don't drop it silently.
            log("receipt not sent, bridge stopping: $text")
            return
        }
        // Where it goes is read now, not when the queue reaches it: a setup save in between
        // mustn't send it to the new server or ack topic (QA r1). Preference reads only: the
        // password is decrypted at send time, on the executor, never on the caller's thread.
        val dest = AckDestination.current(this)
        try {
            acks.execute {
                try {
                    val code = dest.post(text)
                    if (code != 200) log("receipt failed: HTTP $code: $text")
                } catch (_: CredentialUnreadableException) {
                    log("$CREDENTIAL_UNREADABLE; receipt not sent: $text")
                } catch (e: Exception) {
                    log("receipt failed: ${e.javaClass.simpleName}: $text")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shut down between the check above and here.
            log("receipt not sent, bridge stopping: $text")
        }
    }

    // ---- bookkeeping --------------------------------------------------------

    private fun log(text: String) = BridgeLog.append(this, text)

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
        val members = Config.familyMembers(this)
        val refused = Config.refusedMembers(this)
        // The names come from setup and stay on this phone; the log has only the slugs.
        val refusedNames = members.filter { it.slug in refused }.map { it.name }
        val status = statusNotificationFor(stopState(), hostOf(Config.server(this)), members.size, refusedNames)
        val b = Notification.Builder(this, CH_STATUS)
            .setContentTitle("Muse Bridge")
            .setContentText(status.text)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentIntent(ownerScreen())
            .setDeleteIntent(serviceAction(ACTION_REPOST_STATUS, 4))
            // The STOP buttons must show on the lock screen even when the phone hides sensitive
            // notification content there, so the notification is public: a redacted one shows
            // only its public version, without buttons. The refused members' names are its only
            // private part. Android shows the public version, which names nobody, when the owner
            // sets this channel to hide sensitive content; with the phone-wide setting alone a
            // public notification still shows in full, names included.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPublicVersion(
                Notification.Builder(this, CH_STATUS)
                    .setContentTitle("Muse Bridge")
                    .setContentText(status.publicText)
                    .setSmallIcon(R.drawable.ic_bridge)
                    .build()
            )
            .setOngoing(true)
            // Re-posted on every STOP change; only the first post may alert.
            .setOnlyAlertOnce(true)
            // Its own group, so Android doesn't bundle it with announcements, which would hide
            // the STOP buttons behind the bundle.
            .setGroup(GROUP_STATUS)
        for (button in status.buttons) {
            // Request codes 2 and 3: distinct from Stop ringing (1), the repost (4) and the
            // confirm prompt's Open (5) and Not now (6).
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
        const val LOG_FILE = BridgeLog.FILE
        /** Open RustDesk on the confirm prompt, sent by ConfirmActivity once the phone is unlocked. */
        internal const val ACTION_CONFIRM_OPEN = "com.intfar.mabridge.CONFIRM_OPEN"
        /** Not now on the confirm prompt. */
        internal const val ACTION_CONFIRM_DECLINE = "com.intfar.mabridge.CONFIRM_DECLINE"
        /** The confirm request's message id, on both confirm actions. */
        internal const val EXTRA_CONFIRM_ID = "confirm_id"
        private const val ACTION_STOP_RING = "com.intfar.mabridge.STOP_RING"
        private const val ACTION_REPOST_STATUS = "com.intfar.mabridge.REPOST_STATUS"
        private const val CH_STATUS = "mab_status_v2"
        /** The silent status channel used before mab_status_v2; lock screens hide it by default. */
        private const val CH_STATUS_OLD = "mab_status"
        private const val CH_ANNOUNCE = "mab_announce"
        private const val GROUP_STATUS = "mab_status"
        private const val GROUP_RING = "mab_ring"
        private const val CH_CONFIRM = "mab_confirm"
        private const val GROUP_CONFIRM = "mab_confirm"
        private const val STATUS_NOTIFICATION = 1
        private const val RING_NOTIFICATION = 2
        private const val CONFIRM_NOTIFICATION = 3
        /** Tags the confirm timer's callback, so ending the request removes it. */
        private val CONFIRM_TOKEN = Any()
        private val RUSTDESK = Command.Launch("rustdesk")
        private const val PROBE_TIMEOUT_MS = 5_000
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

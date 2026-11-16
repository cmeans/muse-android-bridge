package com.intfar.mabridge.protocol

/**
 * The owner screen's words, kept apart from the Android views so they can be tested:
 * the status card's headline, the recent-activity lines, and what a setup change does.
 */

/** The host part of a server address, as the owner screen shows it: `https://ntfy.example.com:8443/` -> `ntfy.example.com:8443`. */
fun hostOf(server: String): String =
    server.trim().substringAfter("://").trimEnd('/')

/** Who sent a command, in the owner's words: the owner channel is the owner's own Muse. */
fun askedBy(channel: String): String = if (channel == "owner") "your Muse" else "family"

/** The status card's state: its look follows [kind]. */
enum class StatusKind { OK, STOPPED, OFF }

data class OwnerHeadline(val kind: StatusKind, val title: String, val detail: String)

/**
 * What the status card says. [lastConnectedMs] is 0 when the bridge never connected;
 * [nowMs] is passed in so the wording is testable.
 */
fun ownerHeadline(
    setupComplete: Boolean,
    running: Boolean,
    stop: StopState,
    host: String,
    lastConnectedMs: Long,
    nowMs: Long,
): OwnerHeadline {
    val since = if (lastConnectedMs == 0L) "not connected yet" else "last connected ${ago(nowMs - lastConnectedMs)}"
    return when {
        !setupComplete -> OwnerHeadline(StatusKind.OFF, "Not set up", "Tap Change setup to enter the server and password.")
        !running -> OwnerHeadline(StatusKind.OFF, "Bridge is off", "Nothing can reach this phone until it's started.")
        stop.all -> OwnerHeadline(
            StatusKind.STOPPED, "Everything stopped",
            "Nothing can open apps or ring this phone. Commands are logged and refused. Server: $host, $since.",
        )
        // "last connected" is when the stream last opened, not proof it's open now: the
        // live connection state is #16's.
        else -> OwnerHeadline(StatusKind.OK, "Listening", "Server: $host · $since")
    }
}

/** A duration in the owner's words: "just now", "5 min ago", "3 h ago", "2 days ago". */
fun ago(ms: Long): String {
    val min = ms.coerceAtLeast(0) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

/** One line of the recent-activity list: the log's time (HH:mm) and what happened. */
data class RecentItem(val time: String, val text: String, val refused: Boolean = false)

private val LOG_LINE = Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}:\d{2}):\d{2} \| (.*)""")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val OUTCOME = Regex("""([a-z-]+) \((owner|family)(?:, [^)]*)?\): (.*)""")

/**
 * A bridge.log line as the owner reads it, or null for lines the short list leaves out
 * (connection chatter, duplicates, receipts): those stay in "Show all activity".
 * [today] is the phone's date as `yyyy-MM-dd`: lines from another day show their date
 * ("Nov 12, 22:19"), so yesterday's event can't pass for today's. Null shows the time only.
 */
fun recentItemFor(line: String, registry: Registry = PILOT_REGISTRY, today: String? = null): RecentItem? {
    val m = LOG_LINE.matchEntire(line) ?: return null
    val (y, mo, d, hm, text) = m.destructured
    val time = if (today == null || today == "$y-$mo-$d") hm
    else "${MONTHS.getOrElse(mo.toInt() - 1) { mo }} ${d.toInt()}, $hm"
    plainEvent(text)?.let { return RecentItem(time, it) }
    val o = OUTCOME.matchEntire(text) ?: return null
    val (result, channel, rest) = o.destructured
    val who = askedBy(channel)
    // The command is the start of the rest: details follow after ": " or " (".
    val command = rest.substringBefore(": ").substringBefore(" (")
    val what = describe(command, registry)
    return when (result) {
        // An unverified launch ran, but nothing confirmed the app came to the front.
        "ok" -> RecentItem(time, "${what.done}${if ("unverified" in rest) " (not checked)" else ""} · asked by $who")
        "disabled" -> RecentItem(time, "Refused, stopped: ${what.name} · $who", refused = true)
        "rate-limited" -> RecentItem(time, "Refused, too many: ${what.name} · $who", refused = true)
        "unknown-command" -> RecentItem(time, "Refused an unknown command · $who", refused = true)
        "not-installed" -> RecentItem(time, "Not installed: ${what.name} · $who", refused = true)
        "cannot-launch" -> RecentItem(time, "Couldn't open ${what.name}: Show over other apps is off · $who", refused = true)
        "cannot-announce" -> RecentItem(time, "Not done, notifications are off: ${what.name} · $who", refused = true)
        "blocked" -> RecentItem(time, "Tried to open ${what.name}; it didn't come to the front · $who", refused = true)
        "failed" -> RecentItem(time, "Failed: ${what.name} · $who", refused = true)
        "not-implemented" -> RecentItem(time, "Not available yet: ${what.name} · $who", refused = true)
        // A stale line logs the message body, whatever it says: name it only when it's a real
        // command, so a sender's text never shows as the owner screen's own words.
        "dropped-stale" -> {
            val ch = if (channel == "owner") Channel.OWNER else Channel.FAMILY
            if (parseCommand(command, ch, registry) != null) RecentItem(time, "Ignored an old command: ${what.name} · $who", refused = true)
            else RecentItem(time, "Ignored an old message · $who", refused = true)
        }
        else -> null
    }
}

private fun plainEvent(text: String): String? = when {
    text == "service started" -> "Bridge started"
    text == "service stopped" -> "Bridge stopped"
    text == "subscribed" -> "Connected"
    text.startsWith("setup changed: ") -> "Setup changed: " + text.removePrefix("setup changed: ")
    text == "STOP all switched on from the notification" -> "You turned on Stop everything (notification)"
    text == "STOP family switched on from the notification" -> "You turned on Stop family (notification)"
    text.startsWith("ring ended: ") -> "Ringing ended"
    text == LOG_STOP_TAPPED -> "You stopped the bridge"
    text == LOG_NOT_STARTED_REBOOT -> "Didn't start after a reboot: the bridge is stopped"
    text == LOG_NOT_STARTED_UPDATE -> "Didn't start after an update: the bridge is stopped"
    text == LOG_KEPT_OFF_AT_UPDATE -> "Update: the bridge stays off until you tap Start bridge"
    else -> null
}

private data class Described(val name: String, val done: String)

private fun describe(command: String, registry: Registry): Described {
    if (command.startsWith("launch ")) {
        val app = registry.apps[command.removePrefix("launch ")]?.displayName ?: command.removePrefix("launch ")
        return Described(app, "Opened $app")
    }
    return when (command) {
        "ring find" -> Described("ring", "Rang the phone")
        "status report" -> Described("status report", "Shared status")
        else -> Described(command, command)
    }
}

/** The fields the setup screen edits. The password isn't here: it's write-only. */
data class Setup(val server: String, val slug: String, val username: String)

/**
 * What saving the setup screen changes. [needsConfirm] is true when the server or the
 * device name changes on a phone that was already set up: whoever runs the new server, or
 * reads the new topics, can open apps and ring this phone, so the owner confirms with the
 * old and new hosts in front of them. [logLine] names what changed, never the password.
 */
data class SetupChange(val needsConfirm: Boolean, val logLine: String?)

fun setupChangeFor(old: Setup, new: Setup, passwordReplaced: Boolean): SetupChange {
    val parts = buildList {
        if (old.server != new.server) add("server ${hostOf(old.server).ifEmpty { "(none)" }} -> ${hostOf(new.server)}")
        if (old.slug != new.slug) add("device ${old.slug.ifEmpty { "(none)" }} -> ${new.slug}")
        // The username isn't validated like the others, so it's logged as an excerpt (logExcerpt).
        if (old.username != new.username) add("username ${logExcerpt(old.username).ifEmpty { "(none)" }} -> ${logExcerpt(new.username)}")
        if (passwordReplaced) add("password replaced")
    }
    val wasSetUp = old.server.isNotEmpty()
    val confirm = wasSetUp && (old.server != new.server || old.slug != new.slug)
    return SetupChange(confirm, if (parts.isEmpty()) null else "setup changed: " + parts.joinToString("; "))
}

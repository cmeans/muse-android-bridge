package org.intfar.mabridge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.intfar.mabridge.protocol.PREF_STOP_ALL
import org.intfar.mabridge.protocol.PREF_STOP_FAMILY
import org.intfar.mabridge.protocol.StatusKind
import org.intfar.mabridge.protocol.StopState
import org.intfar.mabridge.protocol.hostOf
import org.intfar.mabridge.protocol.ownerHeadline
import org.intfar.mabridge.protocol.recentItemFor

/**
 * The owner's screen (PROTOCOL.md §7): a status card, two large STOP switches, what the
 * bridge is allowed to do, and recent activity in plain words. The helper's setup is a
 * separate screen behind "Change setup…" (SetupActivity), so it isn't one stray tap away.
 */
class MainActivity : Activity() {
    private lateinit var ui: Ui

    private lateinit var phoneLine: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var tiles: LinearLayout
    private lateinit var batteryTile: TextView
    private lateinit var museTile: TextView
    private lateinit var familyTile: TextView
    private lateinit var startButton: Button

    private lateinit var familyCard: LinearLayout
    private lateinit var allCard: LinearLayout
    private lateinit var allSubtitle: TextView
    private lateinit var familySwitch: Switch
    private lateinit var allSwitch: Switch

    private lateinit var permissions: LinearLayout
    private lateinit var recentList: LinearLayout
    private lateinit var fullLog: TextView
    private lateinit var showAll: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        // Rotating recreates the screen; the layout is picked again for the new width.
        val wide = resources.configuration.screenWidthDp >= WIDE_DP
        val first = ui.column()
        val second = ui.column()

        first.addView(header())
        first.addView(statusCard(), ui.matchWidth(12))

        first.addView(ui.sectionLabel("STOP"))
        familyCard = stopCard(PREF_STOP_FAMILY, "Stop family", "Ignore family. Your Muse still works.").also {
            familySwitch = it.second
        }.first
        first.addView(familyCard, ui.matchWidth())
        val all = stopCard(PREF_STOP_ALL, "Stop everything", "Nothing can open apps or ring.")
        allCard = all.first
        allSwitch = all.second
        allSubtitle = allCard.getChildAt(0).let { (it as LinearLayout).getChildAt(1) as TextView }
        first.addView(allCard, ui.matchWidth(10))

        // In two columns the right one starts level with the status card, not the header.
        second.addView(ui.sectionLabel("PERMISSIONS").apply { if (wide) setPadding(ui.dp(2), ui.dp(4), 0, ui.dp(8)) })
        permissions = ui.card(padV = 4)
        second.addView(permissions, ui.matchWidth())

        second.addView(ui.sectionLabel("RECENT"))
        recentList = ui.card(padV = 4)
        second.addView(recentList, ui.matchWidth())
        showAll = ui.linkButton("Show all activity") { toggleFullLog() }
        second.addView(showAll)
        fullLog = ui.text("", 12f, R.color.text_muted).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        second.addView(fullLog, ui.matchWidth())

        second.addView(ui.linkButton("Change setup…", R.color.text_muted) {
            startActivity(Intent(this, SetupActivity::class.java))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(24) })

        val root: LinearLayout = if (wide) {
            ui.row().apply {
                gravity = Gravity.TOP
                addView(first, ui.weighted())
                addView(second, ui.weighted().apply { marginStart = ui.dp(24); topMargin = ui.dp(52) })
            }
        } else {
            ui.column().apply {
                addView(first, ui.matchWidth())
                addView(second, ui.matchWidth())
            }
        }
        root.setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(16))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        if (savedInstanceState?.getBoolean(STATE_FULL_LOG) == true) toggleFullLog()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FULL_LOG, fullLog.visibility == View.VISIBLE)
    }

    private fun header(): View {
        val icon = FrameLayout(this).apply {
            background = ui.rounded(R.color.bridge_blue, 12)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_bridge_white)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
        }
        val names = ui.column().apply {
            addView(ui.text("Muse Bridge", 20f, weight = 700).apply { isFocusable = true })
            phoneLine = ui.text("", 13f, R.color.text_muted)
            addView(phoneLine)
        }
        return ui.row(12).apply {
            addView(icon, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            addView(names)
        }
    }

    private fun statusCard(): View {
        statusDot = ui.dot(R.color.ok)
        statusTitle = ui.text("", 22f, weight = 700)
        statusDetail = ui.text("", 15f, R.color.text_muted)
        fun tile(label: String): Pair<View, TextView> {
            val value = ui.text("", 17f, weight = 600)
            val box = ui.column(2).apply {
                background = ui.rounded(R.color.tile_bg, 12)
                setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                addView(ui.text(label, 12f, R.color.text_muted))
                addView(value)
                // TalkBack reads each tile as one item: "Battery, 96%".
                isFocusable = true
            }
            return box to value
        }
        val battery = tile("Battery").also { batteryTile = it.second }
        val muse = tile("Your Muse").also { museTile = it.second }
        val family = tile("Family").also { familyTile = it.second }
        tiles = ui.row(8).apply {
            addView(battery.first, ui.weighted())
            addView(muse.first, ui.weighted())
            addView(family.first, ui.weighted())
        }
        startButton = ui.primaryButton("Start bridge") { BridgeControl.start(this); refresh() }
        statusCard = ui.card(radius = 20).apply {
            addView(ui.row(12).apply {
                addView(statusDot)
                addView(statusTitle)
            })
            addView(statusDetail, ui.matchWidth(10))
            addView(tiles, ui.matchWidth(14))
            addView(startButton, ui.matchWidth(14))
        }
        return statusCard
    }

    /** A large STOP card: the whole card toggles its switch. State survives reboot (§7). */
    private fun stopCard(key: String, title: String, subtitle: String): Pair<LinearLayout, Switch> {
        val prefs = Config.prefs(this)
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, false)
            contentDescription = title
            setOnCheckedChangeListener { _, on ->
                // The running service hears the change itself and updates its notification.
                if (prefs.getBoolean(key, false) != on) prefs.edit().putBoolean(key, on).apply()
                refresh()
            }
        }
        val texts = ui.column(2).apply {
            addView(ui.text(title, 18f, weight = 600))
            addView(ui.text(subtitle, 13f, R.color.text_muted))
        }
        val card = ui.card(padV = 14).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(64)
            addView(texts, ui.weighted())
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
        return card to sw
    }

    // A STOP button on the notification, or the service starting and stopping, can change
    // what this screen shows while it's open (the shade over it doesn't pause it).
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> syncSwitches() }

    private fun syncSwitches() {
        val prefs = Config.prefs(this)
        // Unchanged values are skipped, so setting them doesn't fire the change listeners again.
        if (familySwitch.isChecked != prefs.getBoolean(PREF_STOP_FAMILY, false)) {
            familySwitch.isChecked = prefs.getBoolean(PREF_STOP_FAMILY, false)
        }
        if (allSwitch.isChecked != prefs.getBoolean(PREF_STOP_ALL, false)) {
            allSwitch.isChecked = prefs.getBoolean(PREF_STOP_ALL, false)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Config.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        syncSwitches()
    }

    override fun onPause() {
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun refresh() {
        if (!::recentList.isInitialized) return
        val prefs = Config.prefs(this)
        val stop = StopState(all = Config.stopAll(this), family = Config.stopFamily(this))
        val setupComplete = Config.missingSetup(this).isEmpty()
        val running = prefs.getBoolean("service_running", false)
        val slug = Config.deviceSlug(this)
        phoneLine.text = if (slug.isEmpty()) "Not set up yet" else "This phone: $slug"

        val h = ownerHeadline(
            setupComplete, running, stop, hostOf(Config.server(this)),
            prefs.getLong("last_connected", 0L), System.currentTimeMillis(),
        )
        statusTitle.text = h.title
        statusDetail.text = h.detail
        val stopped = h.kind == StatusKind.STOPPED
        statusCard.background = if (stopped) ui.rounded(R.color.stop_card_bg, 20, R.color.stop_card_border)
        else ui.rounded(R.color.card_bg, 20)
        statusTitle.setTextColor(ui.color(if (stopped) R.color.stop_title else R.color.text_main))
        statusDetail.setTextColor(ui.color(if (stopped) R.color.stop_text else R.color.text_muted))
        statusDot.background = (statusDot.background as android.graphics.drawable.GradientDrawable).apply {
            setColor(ui.color(when (h.kind) { StatusKind.OK -> R.color.ok; StatusKind.STOPPED -> R.color.stop; StatusKind.OFF -> R.color.off }))
        }
        tiles.visibility = if (h.kind == StatusKind.OK) View.VISIBLE else View.GONE
        startButton.visibility = if (setupComplete && !running) View.VISIBLE else View.GONE
        val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryTile.text = if (battery in 0..100) "$battery%" else "?"
        onOff(museTile, !stop.all)
        onOff(familyTile, !stop.all && !stop.family)

        // Stop everything turns its card red while it's on, with the way back spelled out.
        allCard.background = if (stop.all) ui.rounded(R.color.stop_card_bg, 16, R.color.stop_card_border)
        else ui.rounded(R.color.card_bg, 16, R.color.card_border)
        allSubtitle.text = if (stop.all) "On. Turn off to let commands in again." else "Nothing can open apps or ring."
        allSubtitle.setTextColor(ui.color(if (stop.all) R.color.stop_text else R.color.text_muted))
        familyCard.background = if (stop.family && !stop.all) ui.rounded(R.color.stop_card_bg, 16, R.color.stop_card_border)
        else ui.rounded(R.color.card_bg, 16, R.color.card_border)

        refreshPermissions()
        refreshRecent()
    }

    private fun onOff(view: TextView, on: Boolean) {
        view.text = if (on) "On" else "Off"
        view.setTextColor(ui.color(if (on) R.color.ok else R.color.stop))
    }

    private fun refreshPermissions() {
        permissions.removeAllViews()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val overlaySettings = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        val usageSettings = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        val notificationSettings = {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        // Each row says what the permission is for, so turning one off is an informed choice.
        // An app can't take back its own permissions: both buttons open Android's setting,
        // where the owner switches it. "Fix" for one that's off, "Turn off" for one that's on.
        val rows = listOf(
            Permission(
                "Show notifications", "Announces launches and rings first. Off: they're refused.",
                nm.areNotificationsEnabled(), fix = { requestNotifications() }, turnOff = notificationSettings,
            ),
            Permission(
                "Show over other apps", "Lets the bridge open apps. Off: launches are refused.",
                Settings.canDrawOverlays(this), fix = overlaySettings, turnOff = overlaySettings,
            ),
            // Without it a launch still runs, but its receipt can't confirm the app came to the front.
            Permission(
                "Check apps opened", "Confirms a launched app came to the front. Off: launches are unverified.",
                Config.hasUsageAccess(this), fix = usageSettings, turnOff = usageSettings,
            ),
            Permission(
                "Run in background", "Keeps the bridge listening when the phone sleeps. Off: commands may be late.",
                pm.isIgnoringBatteryOptimizations(packageName),
                fix = {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                },
                // The request screen above can only grant; the full list is where it's taken back.
                turnOff = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            ),
        )
        rows.forEachIndexed { i, p ->
            val texts = ui.column(2).apply {
                addView(ui.text(p.label, 15f))
                addView(ui.text(if (p.allowed) "✓ Allowed" else "Not allowed", 13f, if (p.allowed) R.color.ok else R.color.danger_text, 600))
                addView(ui.text(p.why, 13f, R.color.text_muted))
            }
            val button = if (p.allowed) ui.outlineButton("Turn off") { p.turnOff() } else ui.primaryButton("Fix") { p.fix() }
            button.apply {
                contentDescription = (if (p.allowed) "Turn off: " else "Fix: ") + p.label
                minHeight = ui.dp(44)
                minimumHeight = ui.dp(44)
                minWidth = ui.dp(88)
                minimumWidth = ui.dp(88)
                textSize = 15f
            }
            val row = ui.row(12).apply {
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                addView(texts, ui.weighted())
                addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(44)))
            }
            permissions.addView(row, ui.matchWidth())
            if (i < rows.lastIndex) permissions.addView(divider())
        }
    }

    private class Permission(
        val label: String, val why: String, val allowed: Boolean,
        val fix: () -> Unit, val turnOff: () -> Unit,
    )

    private fun divider() = View(this).apply {
        setBackgroundColor(ui.color(R.color.card_border))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(1))
    }

    private fun refreshRecent() {
        recentList.removeAllViews()
        val lines = BridgeLog.lastLines(this, 200)
        val items = lines.asReversed().mapNotNull { recentItemFor(it) }.take(5)
        if (items.isEmpty()) {
            recentList.addView(ui.text("Nothing yet.", 15f, R.color.text_muted).apply {
                setPadding(0, ui.dp(12), 0, ui.dp(12))
            })
        }
        items.forEachIndexed { i, item ->
            val row = ui.row(8).apply {
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                addView(ui.text(item.text, 15f, if (item.refused) R.color.danger_text else R.color.text_main), ui.weighted())
                addView(ui.text(item.time, 13f, R.color.text_muted))
                isFocusable = true
            }
            recentList.addView(row, ui.matchWidth())
            if (i < items.lastIndex) recentList.addView(divider())
        }
        if (fullLog.visibility == View.VISIBLE) fullLog.text = fullLogText(lines)
    }

    private fun fullLogText(lines: List<String>) =
        lines.asReversed().joinToString("\n").ifBlank { "(no activity yet)" }

    private fun toggleFullLog() {
        val show = fullLog.visibility != View.VISIBLE
        fullLog.visibility = if (show) View.VISIBLE else View.GONE
        showAll.text = if (show) "Hide full log" else "Show all activity"
        if (show) fullLog.text = fullLogText(BridgeLog.lastLines(this, 200))
    }

    /**
     * Asks for notifications once with Android's prompt; after that (a second refusal makes
     * the prompt stop showing) the Fix button opens the app's notification settings.
     */
    private fun requestNotifications() {
        val prefs = Config.prefs(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(PREF_ASKED_NOTIFICATIONS, false)
        ) {
            prefs.edit().putBoolean(PREF_ASKED_NOTIFICATIONS, true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else if (!(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private companion object {
        const val PREF_ASKED_NOTIFICATIONS = "asked_notifications"
        const val STATE_FULL_LOG = "full_log_shown"
        /** From this width (landscape on most phones, tablets) the screen uses two columns. */
        const val WIDE_DP = 600
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}

package org.intfar.mabridge

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.intfar.mabridge.protocol.Setup
import org.intfar.mabridge.protocol.hostOf
import org.intfar.mabridge.protocol.isValidServer
import org.intfar.mabridge.protocol.isValidSlug
import org.intfar.mabridge.protocol.setupChangeFor

/**
 * The helper's setup: server, device name, username and password. It sits behind
 * "Change setup…" on the owner screen. Moving a set-up phone to another server or device
 * name asks first, showing the old and new host; every saved change is logged, and a
 * running bridge restarts so the change applies at once.
 */
class SetupActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var server: EditText
    private lateinit var slug: EditText
    private lateinit var user: EditText
    private lateinit var pwField: EditText
    private lateinit var result: TextView
    private lateinit var bridgeLine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val prefs = Config.prefs(this)
        val root = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24)) }

        root.addView(ui.row(4).apply {
            addView(ImageButton(this@SetupActivity).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = ui.tint(R.color.text_main)
                background = null
                contentDescription = "Back"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            addView(ui.text("Change setup", 20f, weight = 700))
        })

        root.addView(ui.text(
            "Only change this if you set it up yourself. Nobody from Muse, Meta or a help line will ever ask you to.",
            14f, R.color.warn_text,
        ).apply {
            background = ui.rounded(R.color.warn_bg, 14, R.color.warn_border, 1)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        }, ui.matchWidth(8))

        fun label(t: String) = ui.text(t, 14f, weight = 600).apply { setPadding(0, ui.dp(16), 0, ui.dp(6)) }
        // The hint inside a field disappears as soon as someone types, so it only shows the
        // pattern; the helper line below carries the instructions.
        fun helper(t: String) = ui.text(t, 13f, R.color.text_muted).apply { setPadding(0, ui.dp(6), 0, 0) }
        fun field(id: Int, key: String, hint: String) = EditText(this).apply {
            this.id = id
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 16f
            setTextColor(ui.color(R.color.text_main))
            setHintTextColor(ui.color(R.color.text_muted))
            background = ui.rounded(R.color.card_bg, 12, R.color.field_border, 1)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            // After a rotation the restored text wins over the saved setup.
            if (savedInstanceState == null) setText(prefs.getString(key, "") ?: "")
        }

        root.addView(label("Server address"))
        server = field(R.id.setup_server, "server", "https://ntfy.example.com").also { root.addView(it, ui.matchWidth()) }
        root.addView(helper("The bridge's ntfy server, from whoever runs it. Starts with https://."))
        root.addView(label("Device name"))
        slug = field(R.id.setup_slug, "slug", "name-device").also { root.addView(it, ui.matchWidth()) }
        root.addView(helper("The name this phone was given on the server. Lowercase letters, digits and hyphens. Example: alex-pixel"))
        root.addView(label("Phone username"))
        user = field(R.id.setup_username, "username", "name-device-phone").also { root.addView(it, ui.matchWidth()) }
        root.addView(helper("Usually the device name followed by -phone. Filled in for you when it's empty."))
        // PROTOCOL §3 names the phone's user <device>-phone, so offer that once the device
        // name is typed, without overwriting anything the helper entered.
        slug.setOnFocusChangeListener { _, hasFocus ->
            val typed = slug.text.toString().trim()
            if (!hasFocus && user.text.isBlank() && isValidSlug(typed)) user.setText("$typed-phone")
        }

        val creds = CredentialStore(this)
        root.addView(label("Phone password"))
        // Write-only: starts empty, the saved value is never shown, and what's typed isn't
        // kept in the saved screen state either.
        pwField = field(R.id.setup_password, "", "password").apply {
            setText("")
            hint = if (creds.isSet()) "Password saved (type to replace)" else "password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // A password input type switches to monospace; keep the other fields' font.
            typeface = android.graphics.Typeface.DEFAULT
            isSaveEnabled = false
        }
        root.addView(pwField, ui.matchWidth())

        root.addView(ui.primaryButton("Save setup") { save() }, ui.matchWidth(20))
        result = ui.text("", 15f).apply { setPadding(0, ui.dp(10), 0, 0) }
        root.addView(result)

        root.addView(ui.sectionLabel("BRIDGE").apply { setPadding(ui.dp(2), ui.dp(28), 0, ui.dp(8)) })
        bridgeLine = ui.text("", 15f, R.color.text_muted)
        root.addView(bridgeLine)
        root.addView(ui.row(10).apply {
            addView(ui.primaryButton("Start bridge") {
                BridgeControl.requestNotifications(this@SetupActivity)
                val missing = BridgeControl.start(this@SetupActivity)
                result.text = if (missing.isEmpty()) "Starting…"
                else "Can't start: missing or invalid ${missing.joinToString(", ")}. If you just typed them, tap Save setup first."
                refreshBridgeLine()
            }, ui.weighted())
            addView(ui.outlineButton("Stop bridge") {
                BridgeControl.stop(this@SetupActivity)
                refreshBridgeLine()
            }, ui.weighted())
        }, ui.matchWidth(10))

        // On a wide screen (landscape, tablets) the form keeps a readable width.
        val content = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(
                minOf(ui.dp(MAX_WIDTH_DP), resources.displayMetrics.widthPixels),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL,
            ))
        }
        setContentView(ScrollView(this).apply {
            addView(content)
            ui.keepClearOfSystemBars(this)
        })
        refreshBridgeLine()
    }

    override fun onResume() {
        super.onResume()
        refreshBridgeLine()
    }

    private fun refreshBridgeLine() {
        val running = Config.prefs(this).getBoolean("service_running", false)
        bridgeLine.text = if (running) "Running, server ${hostOf(Config.server(this))}." else "Not running."
    }

    private fun save() {
        result.text = ""
        val serverText = server.text.toString().trim().lowercase()
        if (!isValidServer(serverText, Config.isDebugBuild(this))) {
            result.text = "Not saved: the server address must be https:// and a host name, optionally with :port, and nothing after it. Example: https://ntfy.example.com"
            return
        }
        val slugText = slug.text.toString().trim().lowercase()
        if (!isValidSlug(slugText)) {
            result.text = "Not saved: the device name must be lowercase letters and digits, joined by single hyphens. Example: alex-pixel"
            return
        }
        // The helper line promises the <device>-phone default: apply it here too, since
        // tapping Save doesn't take focus from the device name field.
        if (user.text.isBlank()) user.setText("$slugText-phone")
        val old = Setup(Config.server(this), Config.deviceSlug(this), Config.username(this))
        val new = Setup(serverText.trimEnd('/'), slugText, user.text.toString().trim())
        val change = setupChangeFor(old, new, passwordReplaced = pwField.text.isNotEmpty())
        if (change.logLine == null) {
            result.text = "Nothing changed."
            return
        }
        if (change.needsConfirm) confirm(old, new) { apply(old, new, change.logLine) } else apply(old, new, change.logLine)
    }

    /**
     * The confirm step. The safe choice comes first and is the filled button; tapping outside
     * or Back changes nothing.
     */
    private fun confirm(old: Setup, new: Setup, onSwitch: () -> Unit) {
        val serverChanged = old.server != new.server
        lateinit var dialog: AlertDialog
        val body = ui.column(10).apply {
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(16))
            val box = ui.column(8).apply {
                background = ui.rounded(R.color.tile_bg, 14)
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                fun line(label: String, value: String, colorId: Int) = ui.row(8).apply {
                    addView(ui.text(label, 14f, R.color.text_muted), ui.weighted())
                    addView(ui.text(value, 15f, colorId, 700))
                }
                if (serverChanged) {
                    addView(line("Now", hostOf(old.server), R.color.text_main))
                    addView(line("New", hostOf(new.server), R.color.danger_text))
                }
                if (old.slug != new.slug) {
                    addView(line("Device now", old.slug, R.color.text_main))
                    addView(line("Device new", new.slug, R.color.danger_text))
                }
            }
            addView(box, ui.matchWidth())
            addView(ui.text(
                if (serverChanged) "Whoever runs the new server will be able to open apps and ring this phone. The change is logged."
                else "Whoever can send to the new device name will be able to open apps and ring this phone. The change is logged.",
                15f, R.color.text_muted,
            ))
            addView(ui.primaryButton(if (serverChanged) "Keep current server" else "Keep current name") {
                dialog.cancel()
            }, ui.matchWidth(6))
            addView(ui.outlineButton("Switch anyway", R.color.danger_text) {
                dialog.dismiss()
                onSwitch()
            }, ui.matchWidth())
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(if (serverChanged) "Switch to a new server?" else "Change this phone's device name?")
            .setView(body)
            .setOnCancelListener { result.text = "Not changed." }
            .create()
        dialog.show()
        confirmDialog = dialog
    }

    /** The open confirm step, so a rotation can close it instead of leaking its window. */
    private var confirmDialog: AlertDialog? = null

    override fun onDestroy() {
        // Rotating mid-confirm closes it; nothing was saved, and the form keeps what was typed.
        confirmDialog?.dismiss()
        super.onDestroy()
    }

    private fun apply(old: Setup, new: Setup, logLine: String) {
        Config.prefs(this).edit()
            .putString("server", new.server)
            .putString("slug", new.slug)
            .putString("username", new.username)
            .apply {
                // State that belonged to the old server or topics would describe the new one:
                // the status card's "last connected", and the id the next subscribe resumes from.
                if (old.server != new.server || old.slug != new.slug) remove("last_connected").remove("last_message_id")
            }
            .commit() // written before the restart below reads it
        if (pwField.text.isNotEmpty()) {
            val pw = CharArray(pwField.text.length).also { pwField.text.getChars(0, it.size, it, 0) }
            CredentialStore(this).save(pw)
            pw.fill('\u0000')
            pwField.text.clear()
            pwField.hint = "Password saved (type to replace)"
        }
        BridgeLog.append(this, logLine)
        BridgeControl.restartIfRunning(this)
        result.text = "Saved."
        refreshBridgeLine()
    }

    private companion object {
        const val MAX_WIDTH_DP = 560
    }
}

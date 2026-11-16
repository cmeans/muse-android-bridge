package com.intfar.mabridge

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.intfar.mabridge.protocol.CREDENTIAL_UNREADABLE
import com.intfar.mabridge.protocol.FamilyMember
import com.intfar.mabridge.protocol.SETUP_RECEIPT_SENT
import com.intfar.mabridge.protocol.setupReceiptFailedLog
import com.intfar.mabridge.protocol.MAX_MEMBERS
import com.intfar.mabridge.protocol.Setup
import com.intfar.mabridge.protocol.Receipts
import com.intfar.mabridge.protocol.ackDestinationChanged
import com.intfar.mabridge.protocol.addedMembers
import com.intfar.mabridge.protocol.encodeMembers
import com.intfar.mabridge.protocol.setupSaveAnswer
import com.intfar.mabridge.protocol.hostOf
import com.intfar.mabridge.protocol.isValidServer
import com.intfar.mabridge.protocol.isValidSlug
import com.intfar.mabridge.protocol.memberProblems
import com.intfar.mabridge.protocol.setupChangeFor
import com.intfar.mabridge.protocol.normalizeMemberName
import kotlin.concurrent.thread

/**
 * The helper's setup: server, device name, username, password and family members. It sits behind
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
    private lateinit var memberRows: LinearLayout
    private lateinit var addMember: Button

    /** One (member id, name) field pair per family member row, in screen order. */
    private val memberFields = mutableListOf<Pair<EditText, EditText>>()

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

        root.addView(label("Family members"))
        root.addView(helper(
            "Each person who can ask this phone for help needs a member id from whoever set up the server. " +
                "The name is what this phone says and shows; it stays on this phone. " +
                "Add a member only after the server has set them up: until then this phone can't hear them.",
        ))
        memberRows = ui.column(10).apply { setPadding(0, ui.dp(10), 0, 0) }
        root.addView(memberRows, ui.matchWidth())
        // Dynamic rows can't share fixed view ids, so a rotation restores them from the saved
        // slugs and names (onSaveInstanceState) instead of through the view hierarchy.
        val savedSlugs = savedInstanceState?.getStringArray(STATE_MEMBER_SLUGS)
        val savedNames = savedInstanceState?.getStringArray(STATE_MEMBER_NAMES)
        addMember = ui.outlineButton("Add family member") { addMemberRow("", "") }.apply { id = R.id.setup_add_member }
        if (savedSlugs != null && savedNames != null && savedSlugs.size == savedNames.size) {
            savedSlugs.indices.forEach { addMemberRow(savedSlugs[it], savedNames[it]) }
        } else {
            Config.familyMembers(this).forEach { addMemberRow(it.slug, it.name) }
        }
        root.addView(addMember, ui.matchWidth(10))
        refreshAddMember()

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_MEMBER_SLUGS, memberFields.map { it.first.text.toString() }.toTypedArray())
        outState.putStringArray(STATE_MEMBER_NAMES, memberFields.map { it.second.text.toString() }.toTypedArray())
    }

    /** A member row: id, name and Remove, stacked so each stays usable at the largest font size. */
    private fun addMemberRow(slugText: String, nameText: String) {
        fun input(hint: String, value: String, label: String) = EditText(this).apply {
            this.hint = hint
            contentDescription = label
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 16f
            minHeight = ui.dp(48)
            setTextColor(ui.color(R.color.text_main))
            setHintTextColor(ui.color(R.color.text_muted))
            background = ui.rounded(R.color.card_bg, 12, R.color.field_border, 1)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            setText(value)
        }
        val idField = input("member id, e.g. m1", slugText, "Member id")
        val nameField = input("name, e.g. Alex", nameText, "Member name")
        val pair = idField to nameField
        val row = ui.column(8).apply {
            background = ui.rounded(R.color.tile_bg, 14)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }
        row.addView(idField, ui.matchWidth())
        row.addView(nameField, ui.matchWidth())
        val remove = ui.outlineButton("Remove") {
            memberRows.removeView(row)
            memberFields.remove(pair)
            refreshAddMember()
        }
        remove.tag = REMOVE_TAG
        row.addView(remove, ui.matchWidth())
        memberRows.addView(row, ui.matchWidth())
        memberFields.add(pair)
        refreshAddMember()
    }

    private fun refreshAddMember() {
        // A distinct label per row for TalkBack, renumbered when a row goes.
        for (i in 0 until memberRows.childCount) {
            memberRows.getChildAt(i).findViewWithTag<android.view.View>(REMOVE_TAG)?.contentDescription = "Remove member ${i + 1}"
        }
        addMember.visibility = if (memberFields.size >= MAX_MEMBERS) android.view.View.GONE else android.view.View.VISIBLE
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
        // A row with both fields empty is an untouched new row: ignored, not a problem.
        val members = memberFields
            .map { (id, name) ->
                FamilyMember(
                    id.text.toString().trim().lowercase(),
                    normalizeMemberName(name.text.toString().trim()),
                )
            }
            .filter { it.slug.isNotEmpty() || it.name.isNotEmpty() }
        memberProblems(members, slugText).firstOrNull()?.let { problem ->
            val notSaved = "Not saved: $problem" // built apart so lint does not flag a literal in setText
            result.text = notSaved
            return
        }
        val old = Setup(Config.server(this), Config.deviceSlug(this), Config.username(this), Config.familyMembers(this))
        val new = Setup(serverText.trimEnd('/'), slugText, user.text.toString().trim(), members)
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
        val deviceChanged = old.slug != new.slug
        val added = addedMembers(old, new)
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
                if (deviceChanged) {
                    addView(line("Device now", old.slug, R.color.text_main))
                    addView(line("Device new", new.slug, R.color.danger_text))
                }
                if (added.isNotEmpty()) addView(line("Adding member id", added.joinToString(", "), R.color.danger_text))
            }
            addView(box, ui.matchWidth())
            val warning = buildList {
                if (serverChanged) add("Whoever runs the new server will be able to open apps and ring this phone.")
                else if (deviceChanged) add("Whoever can send to the new device name will be able to open apps and ring this phone.")
                if (added.isNotEmpty()) add("Whoever can send to a new member's topic will be able to open apps on this phone.")
            }
            addView(ui.text(warning.joinToString(" ") + " The change is logged.", 15f, R.color.text_muted))
            addView(ui.primaryButton(if (serverChanged) "Keep current server" else if (deviceChanged) "Keep current name" else "Don't add") {
                dialog.cancel()
            }, ui.matchWidth(6))
            addView(ui.outlineButton(if (serverChanged || deviceChanged) "Switch anyway" else "Add anyway", R.color.danger_text) {
                dialog.dismiss()
                onSwitch()
            }, ui.matchWidth())
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(
                if (serverChanged) "Switch to a new server?"
                else if (deviceChanged) "Change this phone's device name?"
                else if (added.size > 1) "Add family members?" else "Add a family member?",
            )
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
        // Before anything new is written: a request waiting for a tap when the server or device
        // name changes is answered on the old server and ack topic, with the old credential
        // (setupSaveAnswer, PROTOCOL §5(e)). Answered at the next start, it would go to the new ones.
        answerWaitingForSave(old, new)
        Config.prefs(this).edit()
            .putString("server", new.server)
            .putString("slug", new.slug)
            .putString("username", new.username)
            .putString("family_members", encodeMembers(new.members))
            .apply {
                // State that belonged to the old server or topics would describe the new one:
                // the status card's "last connected", and the id the next subscribe resumes from.
                if (ackDestinationChanged(old, new)) remove("last_connected").remove("last_message_id")
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

    /**
     * Answers the waiting request, if any, as SETUP_CHANGED when this save moves the receipts'
     * destination. The destination (server, ack topic, username and the password's ciphertext;
     * preference reads only) is read first, from the setup still stored, and the password is
     * decrypted on the send thread. The request is cleared under [WaitingConfirm.lock], which the
     * bridge's own answers hold too, so exactly one of them sends its receipt. It is sent once:
     * if the send fails (no network, or the old server refuses it) it is lost and logged as
     * `receipt failed (old server <host>, mab-<old device>-ack): …`; a sent one is logged as
     * `receipt sent (setup changed)`; if the app is killed before it goes, it is lost with
     * neither line (PROTOCOL §5e). A
     * running bridge's prompt and timer go when the restart after the save stops it; a tap before
     * that finds no request.
     */
    private fun answerWaitingForSave(old: Setup, new: Setup) {
        if (!ackDestinationChanged(old, new)) return
        val ctx = applicationContext
        val dest = AckDestination.current(ctx)
        val (pending, answer) = synchronized(WaitingConfirm.lock) {
            val p = WaitingConfirm.read(ctx) ?: return
            val a = setupSaveAnswer(p, old, new) ?: return
            WaitingConfirm.clear(ctx)
            p to a
        }
        BridgeLog.append(ctx, answer.log)
        val text = Receipts.withId(answer.receipt, pending.id)
        // The lines name the old server and topic, so a failure next to `setup changed: …` doesn't
        // read as the new server refusing it; a sent receipt says so, unlike one lost to a kill.
        thread(name = "mab-setup-receipt") {
            try {
                val code = dest.post(text)
                BridgeLog.append(
                    ctx,
                    if (code == 200) SETUP_RECEIPT_SENT else setupReceiptFailedLog(dest.host, dest.topic, "HTTP $code", text),
                )
            } catch (_: CredentialUnreadableException) {
                BridgeLog.append(ctx, "$CREDENTIAL_UNREADABLE; " + setupReceiptFailedLog(dest.host, dest.topic, "not sent", text))
            } catch (e: Exception) {
                BridgeLog.append(ctx, setupReceiptFailedLog(dest.host, dest.topic, e.javaClass.simpleName, text))
            }
        }
    }

    private companion object {
        const val MAX_WIDTH_DP = 560
        const val REMOVE_TAG = "remove_member"
        const val STATE_MEMBER_SLUGS = "member_slugs"
        const val STATE_MEMBER_NAMES = "member_names"
    }
}

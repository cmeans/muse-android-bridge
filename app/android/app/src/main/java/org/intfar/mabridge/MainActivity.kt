package org.intfar.mabridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The owner's screen (PROTOCOL.md §7): what's on, two large STOP switches,
 * the recent log, and the helper's one-time setup.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Config.prefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        statusView = label("", 18f, bold = true)
        root.addView(statusView)

        // STOP switches: big, separate, labeled for the owner. State is read on
        // every incoming command, so a change applies at once, and survives reboot.
        fun stopSwitch(key: String, title: String) = Switch(this).apply {
            text = title; textSize = 22f
            setPadding(0, pad, 0, pad)
            isChecked = prefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
                refresh()
            }
        }
        root.addView(stopSwitch("stop_family", "Stop family commands"))
        root.addView(stopSwitch("stop_all", "Stop all commands"))

        root.addView(label("Recent activity", 18f, bold = true))
        logView = label("", 12f)
        root.addView(logView)

        // ---- setup (for the helper) ----
        root.addView(label("Setup", 18f, bold = true).apply { setPadding(0, pad * 2, 0, 0) })
        fun field(key: String, hint: String, default: String = "") = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(key, default) ?: default)
        }
        root.addView(label("Server"))
        val server = field("server", "https://ntfy.example.com", "https://ntfy.intfar.com").also { root.addView(it) }
        root.addView(label("Device slug"))
        val slug = field("slug", "e.g. peggy-pixel").also { root.addView(it) }
        root.addView(label("Phone username"))
        val user = field("username", "e.g. peggy-pixel-phone").also { root.addView(it) }

        val creds = CredentialStore(this)
        root.addView(label("Phone password"))
        // Write-only: starts empty, and the saved value is never shown.
        val pwField = EditText(this).apply {
            hint = if (creds.isSet()) "Password saved (type to replace)" else "password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(pwField)

        val setupResult = label("")
        root.addView(Button(this).apply {
            text = "Save setup"
            setOnClickListener {
                prefs.edit()
                    .putString("server", server.text.toString().trim())
                    .putString("slug", slug.text.toString().trim())
                    .putString("username", user.text.toString().trim())
                    .apply()
                if (pwField.text.isNotEmpty()) {
                    val pw = CharArray(pwField.text.length).also { pwField.text.getChars(0, it.size, it, 0) }
                    creds.save(pw)
                    pw.fill('\u0000')
                    pwField.text.clear()
                    pwField.hint = "Password saved (type to replace)"
                }
                setupResult.text = "Saved."
                refresh()
            }
        })
        root.addView(setupResult)

        root.addView(Button(this).apply {
            text = "Start bridge"
            setOnClickListener {
                val missing = Config.missingSetup(this@MainActivity)
                if (missing.isNotEmpty()) {
                    setupResult.text = "Can't start: missing ${missing.joinToString(", ")}."
                    return@setOnClickListener
                }
                requestNotificationPermission()
                startForegroundService(Intent(this@MainActivity, BridgeService::class.java))
                refresh()
            }
        })
        root.addView(Button(this).apply {
            text = "Stop bridge"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BridgeService::class.java))
                refresh()
            }
        })
        root.addView(Button(this).apply {
            text = "Allow opening apps (Display over other apps)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "Allow background running (battery)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!::statusView.isInitialized) return
        val prefs = Config.prefs(this)
        val running = prefs.getBoolean("service_running", false)
        val channels = when {
            Config.stopAll(this) -> "all commands stopped"
            Config.stopFamily(this) -> "owner channel on, family stopped"
            else -> "owner and family channels on"
        }
        val last = prefs.getLong("last_connected", 0L)
        val lastText = if (last == 0L) "never connected" else
            "last connected " + SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(last))
        val overlay = if (!Settings.canDrawOverlays(this))
            "\nOpening apps is not allowed yet (see Setup)." else ""
        statusView.text = "Bridge ${if (running) "running" else "stopped"}: $channels; $lastText.$overlay"

        val f = File(filesDir, BridgeService.LOG_FILE)
        logView.text = if (f.exists()) {
            f.readLines().takeLast(50).asReversed().joinToString("\n").ifBlank { "(log empty)" }
        } else "(no activity yet)"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

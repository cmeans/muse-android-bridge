package org.intfar.mabridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

/**
 * Owner surface: connection setup (helper-performed), service start/stop,
 * the two STOP switches, and the recent log. Deliberately plain — this
 * screen is for the setup helper, not for daily use.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Config.prefs(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f }
        fun field(key: String, hint: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(prefs.getString(key, "") ?: "")
            if (password) inputType = 129 // textPassword
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) prefs.edit().putString(key, text.toString().trim()).apply()
            }
        }

        root.addView(label("Server"))
        root.addView(field("server", "https://ntfy.intfar.com"))
        root.addView(label("Device slug"))
        root.addView(field("slug", "chris-razr"))
        root.addView(label("Phone username"))
        root.addView(field("username", "chris-razr-phone"))
        root.addView(label("Phone password"))
        root.addView(field("password", "password", password = true))

        val startBtn = Button(this).apply {
            text = "Start bridge"
            setOnClickListener {
                requestNotificationPermission()
                val i = Intent(this@MainActivity, BridgeService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }
        }
        val stopBtn = Button(this).apply {
            text = "Stop bridge"
            setOnClickListener { stopService(Intent(this@MainActivity, BridgeService::class.java)) }
        }
        root.addView(startBtn); root.addView(stopBtn)

        val batteryBtn = Button(this).apply {
            text = "Allow background running (battery)"
            setOnClickListener {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            }
        }
        root.addView(batteryBtn)

        root.addView(label("STOP — family channel"))
        root.addView(Switch(this).apply {
            text = "Family commands OFF when switched on"
            isChecked = Config.stopFamily(this@MainActivity)
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("stop_family", v).apply()
            }
        })
        root.addView(label("STOP — everything"))
        root.addView(Switch(this).apply {
            text = "ALL bridge commands OFF when switched on"
            isChecked = Config.stopAll(this@MainActivity)
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("stop_all", v).apply()
            }
        })

        val refreshBtn = Button(this).apply {
            text = "Refresh log"
            setOnClickListener { refreshLog() }
        }
        root.addView(refreshBtn)
        logView = TextView(this).apply { textSize = 12f }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    private fun refreshLog() {
        if (!::logView.isInitialized) return
        val f = File(filesDir, "bridge.log")
        logView.text = if (f.exists()) {
            f.readLines().takeLast(30).joinToString("\n").ifBlank { "(log empty)" }
        } else "(no log yet)"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

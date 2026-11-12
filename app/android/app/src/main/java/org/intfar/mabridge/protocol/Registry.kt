package org.intfar.mabridge.protocol

/** A launchable app (PROTOCOL.md §4.3). */
data class App(val displayName: String, val pkg: String)

/** The per-phone app registry: command slug -> app. */
class Registry(val apps: Map<String, App>)

/** The pilot phone's registry (chris-razr); packages verified over adb on 2026-11-12. */
val PILOT_REGISTRY = Registry(
    mapOf(
        "meet" to App("Meet", "com.google.android.apps.tachyon"),
        "whatsapp" to App("WhatsApp", "com.whatsapp"),
        "facebook" to App("Facebook", "com.facebook.katana"),
        "chrome" to App("Chrome", "com.android.chrome"),
        "muse" to App("Muse", "com.facebook.aura"),
        "rustdesk" to App("RustDesk", "com.carriez.flutter_hbb"),
    )
)

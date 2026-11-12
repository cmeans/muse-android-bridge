package org.intfar.mabridge.protocol

/** A launchable app (PROTOCOL.md §4.3). */
data class App(val displayName: String, val pkg: String)

/** The per-phone app registry: command slug -> app. */
class Registry(val apps: Map<String, App>)

package com.intfar.mabridge.protocol

/** The two command channels of PROTOCOL.md §2.1. */
enum class Channel(private val suffix: String) {
    OWNER("owner"),
    FAMILY("family");

    fun topic(slug: String): String = "mab-$slug-$suffix"
}

/**
 * A device slug per §2.1: lowercase letters and digits in hyphen-separated
 * words, at most 40 characters. Anything else would break the topic names
 * or the subscribe URL (a space, a comma, a slash).
 */
fun isValidSlug(slug: String): Boolean =
    slug.length <= 40 && Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(slug)

/**
 * An ntfy server the bridge can subscribe to: `https://` plus a lowercase host
 * and an optional port (1-65535), with nothing after it but an optional `/`.
 * Release builds are HTTPS only, and a path, query or user part would be glued
 * in front of the topic list in the subscribe URL. With [allowEmulatorHttp]
 * (debug builds), `http://10.0.2.2` with an optional port is also accepted:
 * the only plain-HTTP host src/debug's network config allows.
 */
fun isValidServer(server: String, allowEmulatorHttp: Boolean = false): Boolean {
    // A DNS name is at most 253 characters, in labels of at most 63; checking the length
    // first also keeps a very long input from overflowing the regex engine's stack.
    if (server.length > MAX_SERVER_LENGTH) return false
    val m = SERVER.matchEntire(server) ?: return false
    val (scheme, host, port) = m.destructured
    if (host.length > 253) return false
    if (port.isNotEmpty() && port.toInt() !in 1..65535) return false
    return scheme == "https" || (allowEmulatorHttp && scheme == "http" && host == "10.0.2.2")
}

/** `https://`, a 253-character host, `:65535` and a final `/`. */
private const val MAX_SERVER_LENGTH = 8 + 253 + 6 + 1

private val SERVER =
    Regex("""(https?)://([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*)(?::([0-9]{1,5}))?/?""")

/** The channel a command topic belongs to, or null for any other topic (§5). */
fun channelForTopic(topic: String, slug: String): Channel? =
    Channel.values().firstOrNull { it.topic(slug) == topic }

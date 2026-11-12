package org.intfar.mabridge.protocol

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
 * An ntfy server the bridge can subscribe to: `https://` plus a host (and an
 * optional port), with nothing after it but an optional `/`. Release builds are
 * HTTPS only, and a path, query or user part would be glued in front of the
 * topic list in the subscribe URL.
 */
fun isValidServer(server: String): Boolean =
    Regex("""https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:[0-9]{1,5})?/?""")
        .matches(server)

/** The channel a command topic belongs to, or null for any other topic (§5). */
fun channelForTopic(topic: String, slug: String): Channel? =
    Channel.values().firstOrNull { it.topic(slug) == topic }

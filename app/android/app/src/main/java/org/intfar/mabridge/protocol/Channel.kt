package org.intfar.mabridge.protocol

/** The two command channels of PROTOCOL.md §2.1. */
enum class Channel(private val suffix: String) {
    OWNER("owner"),
    FAMILY("family");

    fun topic(slug: String): String = "mab-$slug-$suffix"
}

/** The channel a command topic belongs to, or null for any other topic (§5). */
fun channelForTopic(topic: String, slug: String): Channel? =
    Channel.values().firstOrNull { it.topic(slug) == topic }

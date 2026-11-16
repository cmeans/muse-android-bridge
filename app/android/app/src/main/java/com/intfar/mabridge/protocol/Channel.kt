package com.intfar.mabridge.protocol

/** The command channels of PROTOCOL.md §2.1. Family commands arrive on per-member topics. */
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
    slug.length <= 40 && SLUG_WORDS.matches(slug)

/** Lowercase letters and digits in hyphen-separated words: the slug grammar, shared with member slugs. */
internal val SLUG_WORDS = Regex("[a-z0-9]+(-[a-z0-9]+)*")

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

/**
 * Where a command came from: its [channel] and, for a family command, the [member] whose topic
 * it arrived on. [member] is null exactly for [Channel.OWNER].
 */
data class Source(val channel: Channel, val member: FamilyMember?)

/** The source as logs name it: `owner` or `family/<slug>`. Never the display name. */
fun Source.logTag(): String =
    if (member == null) channel.name.lowercase() else "${channel.name.lowercase()}/${member.slug}"

/**
 * The source a command topic belongs to, or null for any other topic (§5). The shared
 * `mab-<device>-family` topic is no longer read, and neither is a removed member's.
 */
fun sourceForTopic(topic: String, device: String, members: List<FamilyMember>): Source? {
    if (topic == Channel.OWNER.topic(device)) return Source(Channel.OWNER, null)
    val m = members.firstOrNull { memberTopic(device, it.slug) == topic } ?: return null
    return Source(Channel.FAMILY, m)
}

/** The topics to subscribe to: the owner topic, then each member topic in setup order. */
fun subscribeTopics(device: String, members: List<FamilyMember>): List<String> =
    listOf(Channel.OWNER.topic(device)) + members.map { memberTopic(device, it.slug) }

/**
 * The topics to subscribe to after ntfy refused the whole list (design §2): the [owner] topic,
 * then each member topic whose one-shot probe in [probed] answered 200, in [probed]'s order
 * (setup order). Any other answer counts as refused for this round: a 403, another status, or
 * a network error (passed as any non-200 code); [refusedForLog] says which in the log. The
 * owner's own entry, if present, is the caller's to check: a refused owner topic is a plain
 * `subscribe: HTTP 403`, another status `subscribe: HTTP <code>`, a network error
 * `subscribe error`.
 */
fun topicsAfterProbe(owner: String, probed: Map<String, Int>): List<String> =
    listOf(owner) + probed.filter { (topic, code) -> topic != owner && code == 200 }.keys

/**
 * The refused members as the log names them (design §2): `family/<slug>` for a plain 403, and
 * the answer otherwise, so a flaky probe doesn't read as a missing grant: `(no answer)` for a
 * network error (-1) or a member not probed (null, the bridge stopped mid-round), `(HTTP <code>)`
 * for any other status. Each entry is a slug and its probe's code. Slugs only, never names.
 */
fun refusedForLog(refused: List<Pair<String, Int?>>): String =
    refused.joinToString(", ") { (slug, code) ->
        when (code) {
            403 -> "family/$slug"
            null, -1 -> "family/$slug (no answer)"
            else -> "family/$slug (HTTP $code)"
        }
    }

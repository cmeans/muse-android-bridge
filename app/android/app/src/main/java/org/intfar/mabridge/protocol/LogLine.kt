package org.intfar.mabridge.protocol

private const val EXCERPT_MAX = 64

/**
 * A message body as it may appear in the on-phone log: control characters
 * become `?` (so a body can't forge log lines), at most 64 characters.
 */
fun logExcerpt(body: String): String {
    val clean = body.map { if (it.isISOControl()) '?' else it }.joinToString("")
    return if (clean.length <= EXCERPT_MAX) clean else clean.take(EXCERPT_MAX - 1) + "…"
}

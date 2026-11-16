package com.intfar.mabridge.protocol

private const val EXCERPT_MAX = 64

/**
 * A message body as it may appear in the on-phone log: anything outside
 * printable ASCII becomes `?`, at most 64 characters. Commands are printable
 * ASCII, and this also stops line separators (U+2028), bidi overrides and
 * other control or format characters from drawing a fake log line on screen.
 */
fun logExcerpt(body: String): String {
    val clean = body.map { if (it in ' '..'~') it else '?' }.joinToString("")
    return if (clean.length <= EXCERPT_MAX) clean else clean.take(EXCERPT_MAX - 1) + "…"
}

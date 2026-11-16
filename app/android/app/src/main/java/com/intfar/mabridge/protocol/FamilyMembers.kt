package com.intfar.mabridge.protocol

import java.text.Normalizer

/**
 * A family member the owner has set up: a neutral [slug] that goes into the member's ntfy topic,
 * and a display [name] that never leaves the phone.
 */
data class FamilyMember(val slug: String, val name: String)

/** The most family members one phone can have. */
const val MAX_MEMBERS = 8

/** The longest display name, counted in code points after NFC normalization. */
const val MAX_MEMBER_NAME_LENGTH = 24

/** The longest ntfy topic name. */
const val MAX_TOPIC_LENGTH = 64

private val RESERVED_SLUGS = setOf("owner", "ack", "family")
private const val ZWNJ = 0x200C
private const val ZWJ = 0x200D
private const val RIGHT_QUOTE = 0x2019

/** The ntfy topic a family member's commands arrive on. */
fun memberTopic(device: String, member: String): String = "mab-$device-family-$member"

/**
 * A member slug: [isValidSlug]'s grammar (without its 40-character limit), none of the reserved
 * words, no word `family` (it would collide with the `mab-<device>-family-` prefix), no last word
 * `owner` or `ack` (it would collide with another device's topic), and a whole topic of at most
 * [MAX_TOPIC_LENGTH] characters.
 */
fun isValidMemberSlug(member: String, device: String): Boolean {
    if (!SLUG_WORDS.matches(member)) return false
    if (member in RESERVED_SLUGS) return false
    val words = member.split('-')
    if ("family" in words) return false
    if (words.last() == "owner" || words.last() == "ack") return false
    return memberTopic(device, member).length <= MAX_TOPIC_LENGTH
}

/**
 * A display name: letters of any script, combining marks, spaces, hyphens and apostrophes, with
 * at least one letter and at most [MAX_MEMBER_NAME_LENGTH] code points after NFC normalization.
 * A zero-width joiner or non-joiner is allowed only between a letter or mark and a letter. It
 * takes the raw name and normalizes it itself.
 */
fun isValidMemberName(name: String): Boolean {
    val cps = normalizeName(name).codePoints().toArray()
    if (cps.isEmpty() || cps.size > MAX_MEMBER_NAME_LENGTH) return false
    if (cps.first() == ' '.code || cps.last() == ' '.code) return false
    var hasLetter = false
    var prevLetterOrMark = false
    for ((i, cp) in cps.withIndex()) {
        val isLetter = Character.isLetter(cp)
        val type = Character.getType(cp)
        val isMark = type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
        when {
            isLetter -> hasLetter = true
            isMark -> if (!prevLetterOrMark) return false
            cp == ZWNJ || cp == ZWJ -> {
                if (!prevLetterOrMark) return false
                if (i + 1 >= cps.size || !Character.isLetter(cps[i + 1])) return false
            }
            cp == ' '.code || cp == '-'.code || cp == '\''.code || cp == RIGHT_QUOTE -> Unit
            else -> return false
        }
        prevLetterOrMark = isLetter || isMark
    }
    return hasLetter
}

/**
 * Why the list can't be saved, in words for the owner: empty when it can. Each bad member or
 * repeat yields one problem; names are compared ignoring case after NFC normalization.
 */
fun memberProblems(members: List<FamilyMember>, device: String): List<String> {
    val problems = mutableListOf<String>()
    if (members.size > MAX_MEMBERS) problems += "At most $MAX_MEMBERS family members."
    val slugs = mutableSetOf<String>()
    val names = mutableListOf<String>()
    for (m in members) {
        if (!isValidMemberSlug(m.slug, device)) {
            problems += "\"${m.slug}\" is not a usable member id."
        } else if (!slugs.add(m.slug)) {
            problems += "The member id \"${m.slug}\" is used twice."
        }
        if (!isValidMemberName(m.name)) {
            problems += "\"${m.name}\" is not a usable name (letters, spaces, hyphens and apostrophes; up to " +
                "$MAX_MEMBER_NAME_LENGTH characters)."
        } else {
            val n = normalizeName(m.name)
            if (names.any { it.equals(n, ignoreCase = true) }) {
                problems += "The name \"$n\" is used twice."
            }
            names += n
        }
    }
    return problems
}

/** One `slug<TAB>name` line per member. A valid slug or name holds neither a tab nor a newline. */
fun encodeMembers(members: List<FamilyMember>): String =
    members.joinToString("\n") { "${it.slug}\t${it.name}" }

/**
 * The members in [stored]. A line is kept only when it has exactly two fields, a valid slug for
 * [device], a valid name and a slug not seen before; at most [MAX_MEMBERS] are kept. So a bad
 * stored value can't add a topic. Names come back NFC-normalized.
 */
fun decodeMembers(stored: String, device: String): List<FamilyMember> {
    val out = mutableListOf<FamilyMember>()
    for (line in stored.split('\n')) {
        if (out.size >= MAX_MEMBERS) break
        val fields = line.split('\t')
        if (fields.size != 2) continue
        val (slug, name) = fields
        if (!isValidMemberSlug(slug, device) || !isValidMemberName(name)) continue
        if (out.any { it.slug == slug }) continue
        out += FamilyMember(slug, normalizeName(name))
    }
    return out
}

private fun normalizeName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

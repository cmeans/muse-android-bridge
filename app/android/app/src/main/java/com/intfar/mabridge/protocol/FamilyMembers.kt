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
fun isValidMemberSlug(member: String, device: String): Boolean = memberSlugProblem(member, device) == null

/** The longest member slug for [device]: what's left of [MAX_TOPIC_LENGTH] after `mab-<device>-family-`. */
fun maxMemberSlugLength(device: String): Int = MAX_TOPIC_LENGTH - memberTopic(device, "").length

/** Why [member] isn't a usable slug for [device], in words for the owner, or null when it is. */
private fun memberSlugProblem(member: String, device: String): String? {
    if (member.isEmpty()) return "A member id is missing."
    if (!SLUG_WORDS.matches(member)) {
        return "\"$member\" is not a usable member id: use lowercase letters and digits, in words joined by hyphens."
    }
    val words = member.split('-')
    // The reserved slugs themselves fall here too: `family` is a word, `owner` and `ack` a last word.
    if (member in RESERVED_SLUGS || "family" in words || words.last() == "owner" || words.last() == "ack") {
        return "\"$member\" can't be a member id: it can't contain the word family, or end in owner or ack."
    }
    val max = maxMemberSlugLength(device)
    if (member.length > max) return "\"$member\" is too long: member ids on this phone are at most $max characters."
    return null
}

/**
 * A display name: letters of any script, combining marks, spaces, hyphens and apostrophes, with
 * at least one letter and at most [MAX_MEMBER_NAME_LENGTH] code points after
 * [normalizeMemberName] (NFC, a no-break space as a plain space), with no space at either end and
 * no two spaces in a row. A zero-width joiner or non-joiner is allowed only after a letter or mark
 * and before a letter, a space or the end of the name (a word may end in one, as in a Malayalam
 * chillu written with a ZWJ); every other default-ignorable code point is refused, letters and
 * marks included (the Hangul fillers such as U+3164 are letters with no glyph), so a name can't
 * be blank on screen. A joiner can still sit beside Latin letters, where it draws nothing, so
 * names are compared on [nameKey], which drops it: the app's own words for a sender
 * ([isAppWording]) are refused on that key, and [memberProblems] and [decodeMembers] find repeats
 * on it. It takes the raw name and normalizes it itself.
 */
fun isValidMemberName(name: String): Boolean {
    val normalized = normalizeName(name)
    if (isAppWording(normalized)) return false
    val cps = normalized.codePoints().toArray()
    if (cps.isEmpty() || cps.size > MAX_MEMBER_NAME_LENGTH) return false
    if (cps.first() == ' '.code || cps.last() == ' '.code) return false
    if (hasDoubledSpace(normalized)) return false
    var hasLetter = false
    var prevLetterOrMark = false
    for ((i, cp) in cps.withIndex()) {
        if (cp != ZWNJ && cp != ZWJ && isDefaultIgnorable(cp)) return false
        val isLetter = Character.isLetter(cp)
        val type = Character.getType(cp)
        val isMark = type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
        when {
            isLetter -> hasLetter = true
            isMark -> if (!prevLetterOrMark) return false
            cp == ZWNJ || cp == ZWJ -> {
                if (!prevLetterOrMark) return false
                val next = cps.getOrNull(i + 1)
                if (next != null && next != ' '.code && !Character.isLetter(next)) return false
            }
            cp == ' '.code || cp == '-'.code || cp == '\''.code || cp == RIGHT_QUOTE -> Unit
            else -> return false
        }
        prevLetterOrMark = isLetter || isMark
    }
    return hasLetter
}

/** Whether the [normalized] name has two spaces in a row. */
private fun hasDoubledSpace(normalized: String): Boolean = "  " in normalized

/**
 * The words the app itself uses for a sender: "your Muse" (the owner channel), and "family" and
 * "a family member" (its fallbacks). A member with one of them as a name would read as that
 * sender ("Opening Meet: asked by your Muse"). Compared on [nameKey], so the check catches a name
 * that differs from them only by joiners, spacing or case; lookalike letters from another script
 * (a Cyrillic "у" for "y"), fullwidth forms and ligatures aren't caught.
 */
private fun isAppWording(name: String): Boolean = nameKey(name) in APP_WORDING_KEYS

// Before APP_WORDING_KEYS: top-level values are set in file order, and that one uses nameKey.
private val WHITESPACE_RUN = Regex("\\s+")

private val APP_WORDING_KEYS = setOf("your Muse", "family", "a family member").map { nameKey(it) }.toSet()

/**
 * The form two names are compared on: NFC, zero-width joiners and non-joiners removed, each run
 * of whitespace one space, ends trimmed, and case folded code point by code point
 * (`Character.toLowerCase(Character.toUpperCase(cp))`), never with the default locale, so a
 * Turkish phone compares "ALI" and "ali" as the same name, and the upper-case step folds a final
 * sigma and a dotless i too. Names with one key differ only by joiners, spacing or case.
 */
internal fun nameKey(name: String): String {
    val noJoiners = normalizeName(name).filter { it.code != ZWNJ && it.code != ZWJ }
    val spaced = normalizeName(noJoiners).trim().replace(WHITESPACE_RUN, " ")
    val sb = StringBuilder(spaced.length)
    spaced.codePoints().forEach { sb.appendCodePoint(Character.toLowerCase(Character.toUpperCase(it))) }
    return sb.toString()
}

/**
 * Unicode's Default_Ignorable_Code_Point property (DerivedCoreProperties.txt): code points that
 * render as nothing. Java has no accessor for it, so the ranges are listed.
 */
internal fun isDefaultIgnorable(cp: Int): Boolean = DEFAULT_IGNORABLE.any { cp in it }

private val DEFAULT_IGNORABLE = listOf(
    0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5,
    0x180B..0x180F, 0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164,
    0xFE00..0xFE0F, 0xFEFF..0xFEFF, 0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3,
    0x1D173..0x1D17A, 0xE0000..0xE0FFF,
)

/**
 * Why the list can't be saved, in words for the owner: empty when it can. Each bad member id, bad
 * name or repeat yields one problem that says what to fix; names are compared on [nameKey].
 */
fun memberProblems(members: List<FamilyMember>, device: String): List<String> {
    val problems = mutableListOf<String>()
    if (members.size > MAX_MEMBERS) problems += "At most $MAX_MEMBERS family members."
    val slugs = mutableSetOf<String>()
    val nameKeys = mutableSetOf<String>()
    for (m in members) {
        val slugProblem = memberSlugProblem(m.slug, device)
        if (slugProblem != null) {
            problems += slugProblem
        } else if (!slugs.add(m.slug)) {
            problems += "The member id \"${m.slug}\" is used twice."
        }
        if (isAppWording(m.name)) {
            problems += "\"${m.name}\" can't be a member's name: the phone uses it for your Muse or the family as a whole."
        } else if (hasDoubledSpace(normalizeName(m.name))) {
            problems += "\"${m.name}\" is not a usable name: it has two spaces in a row."
        } else if (!isValidMemberName(m.name)) {
            problems += "\"${m.name}\" is not a usable name (letters, spaces, hyphens and apostrophes; up to " +
                "$MAX_MEMBER_NAME_LENGTH characters)."
        } else {
            if (!nameKeys.add(nameKey(m.name))) {
                problems += "The name \"${normalizeName(m.name)}\" is used twice."
            }
        }
    }
    return problems
}

/** One `slug<TAB>name` line per member. A valid slug or name holds neither a tab nor a newline. */
fun encodeMembers(members: List<FamilyMember>): String =
    members.joinToString("\n") { "${it.slug}\t${it.name}" }

/**
 * The members in [stored]. A line is kept only when it has exactly two fields, a valid slug for
 * [device], a valid name, and a slug and a name ([nameKey]) not seen before; at most
 * [MAX_MEMBERS] are kept. So a bad stored value can't add a topic, or a second member whose name
 * differs from an earlier one only by joiners, spacing or case (lookalike letters from another
 * script aren't caught). Names come back as [normalizeMemberName] gives them.
 */
fun decodeMembers(stored: String, device: String): List<FamilyMember> {
    val out = mutableListOf<FamilyMember>()
    val nameKeys = mutableSetOf<String>()
    for (line in stored.split('\n')) {
        if (out.size >= MAX_MEMBERS) break
        val fields = line.split('\t')
        if (fields.size != 2) continue
        val (slug, name) = fields
        if (!isValidMemberSlug(slug, device) || !isValidMemberName(name)) continue
        if (out.any { it.slug == slug }) continue
        if (!nameKeys.add(nameKey(name))) continue
        out += FamilyMember(slug, normalizeName(name))
    }
    return out
}

/**
 * A display name as the phone stores and checks it: NFC, with each no-break space (U+00A0) as a
 * plain space, so "Mary Ann" typed either way is one name. The setup screen stores this form.
 */
fun normalizeMemberName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFC).replace(' ', ' ')

private fun normalizeName(name: String): String = normalizeMemberName(name)

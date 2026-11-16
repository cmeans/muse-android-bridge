package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5a: per-member family topics, slugs, display names and their stored form. */
class FamilyMembersTest {
    private val device = "p"

    @Test fun memberTopicFormat() {
        assertEquals("mab-pilot-phone-family-m1", memberTopic("pilot-phone", "m1"))
    }

    @Test fun slugGrammar() {
        for (s in listOf("m1", "helper-a")) assertTrue(s, isValidMemberSlug(s, device))
        for (s in listOf("", "M1", "m_1", "-m1", "m1-", "m--1", "m 1", "é1")) {
            assertFalse(s, isValidMemberSlug(s, device))
        }
    }

    @Test fun reservedSlugsAreRefused() {
        for (s in listOf("owner", "ack", "family")) assertFalse(s, isValidMemberSlug(s, device))
    }

    @Test fun collidingSlugsAreRefused() {
        for (s in listOf("family-x", "x-family", "b-owner", "b-ack")) {
            assertFalse(s, isValidMemberSlug(s, device))
        }
        // No device topic ends in -owner-b or -ack-b, so these cannot collide.
        for (s in listOf("owner-b", "ack-b")) assertTrue(s, isValidMemberSlug(s, device))
    }

    @Test fun topicLengthLimit() {
        val longDevice = "d".repeat(40)
        assertTrue(isValidMemberSlug("m".repeat(12), longDevice))
        assertFalse(isValidMemberSlug("m".repeat(13), longDevice))
    }

    @Test fun nameRules() {
        val good = listOf(
            "Alex", "Mary Ann", "O'Neil", "O’Neil", "Jean-Luc", "José", "José",
            "प्रिया",
            "مری‌م",
            "आचार्‍य",
            "a".repeat(24),
        )
        for (n in good) assertTrue(n, isValidMemberName(n))
        val bad = listOf(
            "\u200CAlex", "\u200DAlex", "", " ", "-", "'", "- -",
            "a".repeat(25), "Alex2", "Alex!", "Alex\tB", " Alex", "Alex ",
        )
        for (n in bad) assertFalse(n, isValidMemberName(n))
    }

    @Test fun problemsEmptyForValidMembers() {
        assertTrue(memberProblems(listOf(FamilyMember("m1", "Alex"), FamilyMember("m2", "Bo")), device).isEmpty())
    }

    @Test fun problemsTooManyMembers() {
        val nine = (1..9).map { FamilyMember("m$it", "N" + "x".repeat(it)) }
        assertEquals(1, memberProblems(nine, device).size)
    }

    @Test fun problemsDuplicateSlug() {
        assertEquals(1, memberProblems(listOf(FamilyMember("m1", "Alex"), FamilyMember("m1", "Bo")), device).size)
    }

    @Test fun problemsDuplicateNameIgnoringCaseAndNormalization() {
        assertEquals(1, memberProblems(listOf(FamilyMember("m1", "Alex"), FamilyMember("m2", "alex")), device).size)
        assertEquals(
            1,
            memberProblems(listOf(FamilyMember("m1", "José"), FamilyMember("m2", "José")), device).size,
        )
    }

    @Test fun problemsDuplicateNameIsLocaleIndependent() {
        // Turkish lowercase of "ALI" is "alı"; the comparison must not use it.
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
        try {
            assertEquals(1, memberProblems(listOf(FamilyMember("m1", "ALI"), FamilyMember("m2", "ali")), device).size)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun problemsInvalidSlugAndName() {
        assertEquals(1, memberProblems(listOf(FamilyMember("M1", "Alex")), device).size)
        assertEquals(1, memberProblems(listOf(FamilyMember("m1", "Alex2")), device).size)
    }

    @Test fun encodeRoundTrip() {
        val list = listOf(FamilyMember("m1", "Alex"), FamilyMember("helper-a", "Mary Ann"))
        assertEquals(list, decodeMembers(encodeMembers(list), "p"))
    }

    @Test fun decodeNormalizesName() {
        assertEquals(listOf(FamilyMember("m1", "José")), decodeMembers("m1\tJosé", "p"))
    }

    @Test fun decodeDropsBadLines() {
        val stored = "m1\tAlex\nBAD\tX\nm2\n\tY\nm3\tBe\tC\nfamily-x\tDee\nowner\tEd\nm1\tAl"
        assertEquals(listOf(FamilyMember("m1", "Alex")), decodeMembers(stored, "p"))
    }

    @Test fun decodeStopsAtMax() {
        val stored = (1..9).joinToString("\n") { "m$it\tN" + "x".repeat(it) }
        assertEquals((1..8).map { "m$it" }, decodeMembers(stored, "p").map { it.slug })
    }
    @Test fun namesThatReadAsAnotherSenderAreRefused() {
        // "asked by your Muse" must mean the owner's channel, and the app's own fallbacks
        // ("family", "a family member") mustn't be a member's name either.
        for (n in listOf("your Muse", "Your Muse", "YOUR MUSE", "family", "Family", "a family member", "A Family Member")) {
            assertFalse(n, isValidMemberName(n))
        }
        // Only the whole name is refused.
        for (n in listOf("Muse", "Family Friend", "Your Musette")) assertTrue(n, isValidMemberName(n))
    }

    @Test fun appWordingNameProblemSaysWhy() {
        assertEquals(
            listOf("\"Your Muse\" can't be a member's name: the phone uses it for your Muse or the family as a whole."),
            memberProblems(listOf(FamilyMember("m1", "Your Muse")), device),
        )
    }

    @Test fun appWordingIsLocaleIndependent() {
        // Turkish lowercase of "I" is "ı": a default-locale lowercase would let "FAMILY" through.
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
        try {
            assertFalse(isValidMemberName("FAMILY"))
            assertFalse(isValidMemberName("a family member"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun invisibleFillersAreRefused() {
        // Hangul fillers are letters (Lo) with no glyph: a name of one is blank on screen.
        for (cp in listOf(0x3164, 0x115F, 0x1160, 0xFFA0)) {
            val filler = String(Character.toChars(cp))
            assertFalse("U+%04X alone".format(cp), isValidMemberName(filler))
            assertFalse("Alex + U+%04X".format(cp), isValidMemberName("Alex$filler"))
            assertFalse("U+%04X + Alex".format(cp), isValidMemberName("${filler}Alex"))
        }
        // Default-ignorable marks after a letter render as nothing too.
        for (cp in listOf(0x034F, 0xFE0F, 0x180B, 0xE0100)) {
            assertFalse("Alex + U+%04X".format(cp), isValidMemberName("Alex" + String(Character.toChars(cp))))
        }
    }

    @Test fun invisibleFillerCannotDodgeTheDuplicateCheck() {
        val problems = memberProblems(listOf(FamilyMember("m1", "Alex"), FamilyMember("m2", "Alex\u3164")), device)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].contains("not a usable name"))
    }

    @Test fun joinersStillAllowedBetweenLetters() {
        // The joiners are default-ignorable too, but keep their own rule.
        assertTrue(isValidMemberName("مری\u200Cم"))
        assertTrue(isValidMemberName("आचार्\u200Dय"))
    }

    // QA r1: names are compared on a key (NFC, joiners removed, spaces collapsed, case folded),
    // so an invisible joiner or a doubled space can't dodge the app-wording or duplicate checks.
    @Test fun joinerOrDoubledSpaceCannotDodgeAppWording() {
        for (n in listOf("yo‍ur Muse", "fam‌ily", "a fam‍ily member", "your  Muse", "YOUR‌ MUSE")) {
            assertFalse(n, isValidMemberName(n))
        }
    }

    @Test fun doubledSpaceIsRefused() {
        assertFalse(isValidMemberName("Mary  Ann"))
        assertTrue(isValidMemberName("Mary Ann"))
    }

    @Test fun joinerCannotDodgeTheDuplicateCheck() {
        val problems = memberProblems(listOf(FamilyMember("m1", "Alex"), FamilyMember("m2", "Al‍ex")), device)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].contains("used twice"))
        assertEquals(1, memberProblems(listOf(FamilyMember("m1", "Alex"), FamilyMember("m2", "AL‌EX")), device).size)
    }

    @Test fun joinerAppWordingProblemSaysWhy() {
        val problem = memberProblems(listOf(FamilyMember("m1", "yo‍ur Muse")), device).single()
        assertTrue(problem, problem.contains("the phone uses it for your Muse"))
    }

    @Test fun keyComparisonIsLocaleIndependent() {
        // Turkish lowercase of "I" is "ı": the key must not use the default locale.
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
        try {
            assertFalse(isValidMemberName("FAM‌ILY"))
            assertEquals(1, memberProblems(listOf(FamilyMember("m1", "ALI"), FamilyMember("m2", "a‍li")), device).size)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun acceptedNamesStillAccepted() {
        val good = listOf(
            "مری‌م", // Persian with a ZWNJ
            "आचार्‍य", // Devanagari, a ZWJ after a virama
            "José", "José", "Mary Ann", "O'Neil", "O’Neil", "Jean-Luc",
        )
        for (n in good) assertTrue(n, isValidMemberName(n))
        // All distinct except the two forms of José, which are one name.
        val distinct = good.filter { it != "José" }.mapIndexed { i, n -> FamilyMember("m${i + 1}", n) }
        assertTrue(memberProblems(distinct, device).isEmpty())
    }

    // Pre-QA r2: the case fold needs its upper-case step (final sigma, Turkish dotless i).
    @Test fun caseFoldGoesThroughUpperCase() {
        assertEquals(1, memberProblems(listOf(FamilyMember("m1", "Alı"), FamilyMember("m2", "ALI")), device).size)
        assertEquals(
            1,
            memberProblems(
                listOf(FamilyMember("m1", "ΟΔΟΣ"), FamilyMember("m2", "οδος")),
                device,
            ).size,
        )
    }

    // Pre-QA r2: a joiner may end a word (before a space or at the end); the key still drops it.
    @Test fun joinerBeforeSpaceOrAtEndIsAllowed() {
        for (n in listOf(
            "അവന്‍", // Malayalam chillu by ZWJ at the end
            "അവന്‍ അ", // ...and before a space
            "مری‌ م", // Persian ZWNJ before a space
            "Alex‌", "Alex‍",
        )) {
            assertTrue(n, isValidMemberName(n))
        }
        assertFalse(isValidMemberName("‍Alex"))
        assertFalse(isValidMemberName("Alex ‍"))
        // The QA r1 hole stays closed.
        assertFalse(isValidMemberName("your‍ Muse"))
        assertFalse(isValidMemberName("family‌"))
        assertEquals(1, memberProblems(listOf(FamilyMember("m1", "Alex"), FamilyMember("m2", "Alex‍")), device).size)
    }

    @Test fun nbspIsAPlainSpace() {
        assertTrue(isValidMemberName("Mary Ann"))
        assertEquals("Mary Ann", normalizeMemberName("Mary Ann"))
        assertFalse(isValidMemberName("your Muse"))
        assertFalse(isValidMemberName("Mary  Ann"))
        assertEquals(1, memberProblems(listOf(FamilyMember("m1", "Mary Ann"), FamilyMember("m2", "Mary Ann")), device).size)
        assertEquals(listOf(FamilyMember("m1", "Mary Ann")), decodeMembers("m1\tMary Ann", device))
    }

    @Test fun doubledSpaceHasItsOwnReason() {
        assertEquals(
            listOf("\"Mary  Ann\" is not a usable name: it has two spaces in a row."),
            memberProblems(listOf(FamilyMember("m1", "Mary  Ann")), device),
        )
    }

    @Test fun decodeDropsRepeatedNameKey() {
        val stored = "m1\tAlex\nm2\tAl‍ex\nm3\talex\nm4\tBo"
        assertEquals(listOf(FamilyMember("m1", "Alex"), FamilyMember("m4", "Bo")), decodeMembers(stored, device))
    }

    @Test fun memberIdProblemsSayWhy() {
        fun problem(slug: String, dev: String = device) = memberProblems(listOf(FamilyMember(slug, "Alex")), dev).single()
        assertEquals("A member id is missing.", problem(""))
        assertEquals(
            "\"M1\" is not a usable member id: use lowercase letters and digits, in words joined by hyphens.",
            problem("M1"),
        )
        assertEquals(
            "\"m--1\" is not a usable member id: use lowercase letters and digits, in words joined by hyphens.",
            problem("m--1"),
        )
        for (s in listOf("family", "family-x", "x-family", "owner", "b-owner", "ack", "b-ack")) {
            assertEquals(
                s,
                "\"$s\" can't be a member id: it can't contain the word family, or end in owner or ack.",
                problem(s),
            )
        }
        // 64 - "mab-<40 d's>-family-".length = 12.
        val longDevice = "d".repeat(40)
        assertEquals(
            "\"${"m".repeat(13)}\" is too long: member ids on this phone are at most 12 characters.",
            problem("m".repeat(13), longDevice),
        )
        assertTrue(memberProblems(listOf(FamilyMember("m".repeat(12), "Alex")), longDevice).isEmpty())
    }
}

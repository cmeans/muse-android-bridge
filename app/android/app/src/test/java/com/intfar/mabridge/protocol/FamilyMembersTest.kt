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
            "‌Alex", "Alex‌", "‍Alex", "Alex‍", "", " ", "-", "'", "- -",
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
        val stored = (1..9).joinToString("\n") { "m$it\tName" }
        assertEquals((1..8).map { "m$it" }, decodeMembers(stored, "p").map { it.slug })
    }
}
